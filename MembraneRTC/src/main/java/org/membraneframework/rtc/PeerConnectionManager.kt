package org.membraneframework.rtc

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.membraneframework.rtc.events.OfferData
import org.membraneframework.rtc.media.LocalScreencastTrack
import org.membraneframework.rtc.media.LocalTrack
import org.membraneframework.rtc.media.LocalVideoTrack
import org.membraneframework.rtc.media.TrackBandwidthLimit
import org.membraneframework.rtc.models.QualityLimitationDurations
import org.membraneframework.rtc.models.RTCInboundStats
import org.membraneframework.rtc.models.RTCOutboundStats
import org.membraneframework.rtc.models.RTCStats
import org.membraneframework.rtc.utils.*
import org.webrtc.*
import timber.log.Timber
import java.math.BigInteger
import java.util.*
import kotlin.math.pow

internal class PeerConnectionManager
@AssistedInject constructor(
    @Assisted private val peerConnectionListener: PeerConnectionListener,
    @Assisted private val peerConnectionFactory: PeerConnectionFactoryWrapper
) : PeerConnection.Observer {

    @AssistedFactory
    interface PeerConnectionManagerFactory {
        fun create(
            listener: PeerConnectionListener,
            peerConnectionFactory: PeerConnectionFactoryWrapper
        ): PeerConnectionManager
    }

    private var peerConnection: PeerConnection? = null
    private val peerConnectionMutex = Mutex()
    private val peerConnectionStats = mutableMapOf<String, RTCStats>()

    private var iceServers: List<PeerConnection.IceServer>? = null
    private var config: PeerConnection.RTCConfiguration? = null
    private var queuedRemoteCandidates: MutableList<IceCandidate>? = null
    private val qrcMutex = Mutex()
    private var midToTrackId: Map<String, String> = HashMap<String, String>()

    private val coroutineScope: CoroutineScope =
        ClosableCoroutineScope(SupervisorJob())

    private fun getSendEncodingsFromConfig(simulcastConfig: SimulcastConfig): List<RtpParameters.Encoding> {
        val sendEncodings = Constants.simulcastEncodings()
        simulcastConfig.activeEncodings.forEach {
            sendEncodings[it.ordinal].active = true
        }
        return sendEncodings
    }

    suspend fun addTrack(track: LocalTrack, streamIds: List<String>) {
        val videoParameters =
            (track as? LocalVideoTrack)?.videoParameters ?: (track as? LocalScreencastTrack)?.videoParameters

        val simulcastConfig = videoParameters?.simulcastConfig
        val sendEncodings =
            if (track.rtcTrack().kind() == "video" && simulcastConfig != null && simulcastConfig.enabled) {
                getSendEncodingsFromConfig(simulcastConfig)
            } else {
                listOf(RtpParameters.Encoding(null, true, null))
            }

        peerConnectionMutex.withLock {
            val pc = peerConnection ?: run {
                Timber.e("addTrack: Peer connection not yet established")
                return
            }

            if (videoParameters?.maxBitrate != null) {
                applyBitrate(sendEncodings, videoParameters.maxBitrate)
            }

            pc.addTransceiver(
                track.rtcTrack(),
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                streamIds,
                sendEncodings
            )
            pc.enforceSendOnlyDirection()
        }
    }

    private fun applyBitrate(encodings: List<RtpParameters.Encoding>, maxBitrate: TrackBandwidthLimit) {
        when (maxBitrate) {
            is TrackBandwidthLimit.BandwidthLimit -> splitBitrate(encodings, maxBitrate)
            is TrackBandwidthLimit.SimulcastBandwidthLimit -> encodings.forEach {
                val encodingLimit = maxBitrate.limit[it.rid]?.limit ?: 0
                it.maxBitrateBps = if (encodingLimit == 0) null else encodingLimit * 1024
            }
        }
    }

    private fun splitBitrate(encodings: List<RtpParameters.Encoding>, maxBitrate: TrackBandwidthLimit.BandwidthLimit) {
        if (encodings.isEmpty()) {
            Timber.e("splitBitrate: Attempted to limit bandwidth of the track that doesn't have any encodings")
            return
        }
        if (maxBitrate.limit == 0) {
            encodings.forEach { it.maxBitrateBps = null }
            return
        }

        val k0 = encodings.minByOrNull { it.scaleResolutionDownBy ?: 1.0 }

        val bitrateParts = encodings.sumOf {
            ((k0?.scaleResolutionDownBy ?: 1.0) / (it.scaleResolutionDownBy ?: 1.0)).pow(
                2
            )
        }

        val x = maxBitrate.limit / bitrateParts

        encodings.forEach {
            it.maxBitrateBps =
                (x * ((k0?.scaleResolutionDownBy ?: 1.0) / (it.scaleResolutionDownBy ?: 1.0)).pow(2) * 1024).toInt()
        }
    }

    suspend fun setTrackBandwidth(trackId: String, bandwidthLimit: TrackBandwidthLimit.BandwidthLimit) {
        peerConnectionMutex.withLock {
            val pc = peerConnection ?: run {
                Timber.e("setTrackBandwidth: Peer connection not yet established")
                return
            }
            val sender = pc.senders.find { it.track()?.id() == trackId } ?: run {
                Timber.e("setTrackBandwidth: Invalid trackId: track sender not found")
                return
            }
            val params = sender.parameters

            applyBitrate(params.getEncodings(), bandwidthLimit)

            sender.parameters = params
        }
    }

    suspend fun setEncodingBandwidth(
        trackId: String,
        encoding: String,
        bandwidthLimit: TrackBandwidthLimit.BandwidthLimit
    ) {
        peerConnectionMutex.withLock {
            val pc = peerConnection ?: run {
                Timber.e("setEncodingBandwidth: Peer connection not yet established")
                return
            }
            val sender = pc.senders.find { it.track()?.id() == trackId } ?: run {
                Timber.e("setEncodingBandwidth: Invalid trackId: track sender not found")
                return
            }

            val params = sender.parameters
            val encodingParameters = params.encodings.find { it.rid == encoding } ?: run {
                Timber.e("setEncodingBandwidth: Invalid encoding: encoding not found")
                return
            }

            encodingParameters.maxBitrateBps = bandwidthLimit.limit * 1024

            sender.parameters = params
        }
    }

    suspend fun removeTrack(trackId: String): Boolean {
        peerConnectionMutex.withLock {
            val pc = peerConnection ?: run {
                Timber.e("removeTrack: Peer connection not yet established")
                return false
            }
            pc.transceivers.find { it.sender.track()?.id() == trackId }?.sender?.let {
                pc.removeTrack(it)
                return true
            }
            return false
        }
    }

    private suspend fun setupPeerConnection(localTracks: List<LocalTrack>) {
        if (peerConnection != null) {
            Timber.e("setupPeerConnection: Peer connection already established!")
            return
        }

        assert(config != null)
        val config = this.config!!

        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        config.candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
        config.disableIpv6 = true
        config.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED

        val pc = peerConnectionFactory.createPeerConnection(config, this)
            ?: throw IllegalStateException("Failed to create a peerConnection")

        peerConnectionMutex.withLock {
            this@PeerConnectionManager.peerConnection = pc
        }

        val streamIds = listOf(UUID.randomUUID().toString())

        localTracks.forEach {
            addTrack(it, streamIds)
        }

        peerConnectionMutex.withLock {
            pc.enforceSendOnlyDirection()
        }
    }

    private suspend fun drainCandidates() {
        qrcMutex.withLock {
            this.queuedRemoteCandidates?.let {
                for (candidate in it) {
                    this.peerConnection?.addIceCandidate(candidate)
                }
                this.queuedRemoteCandidates = null
            }
        }
    }

    private fun prepareIceServers(integratedTurnServers: List<OfferData.TurnServer>) {
        if (config != null || iceServers != null) {
            Timber.e("prepareIceServers: Config or ice servers are already initialized, skipping the preparation")
            return
        }

        this.iceServers = integratedTurnServers.map {
            val url = listOf(
                "turn",
                ":",
                it.serverAddr,
                ":",
                it.serverPort.toString(),
                "?transport=",
                it.transport
            ).joinToString("")

            PeerConnection.IceServer.builder(url).setUsername(it.username).setPassword(it.password).createIceServer()
        }

        val config = PeerConnection.RTCConfiguration(iceServers)
        config.iceTransportsType = PeerConnection.IceTransportsType.RELAY
        this.config = config
    }

    private fun addNecessaryTransceivers(tracksTypes: Map<String, Int>) {
        val pc = peerConnection ?: return

        val necessaryAudio = tracksTypes["audio"] ?: 0
        val necessaryVideo = tracksTypes["video"] ?: 0

        var lackingAudio = necessaryAudio
        var lackingVideo = necessaryVideo

        pc.transceivers.filter {
            it.direction == RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
        }.forEach {
            val track = it.receiver.track() ?: return@forEach

            when (track.kind()) {
                "audio" -> lackingAudio -= 1
                "video" -> lackingVideo -= 1
            }
        }

        Timber.d("peerConnection adding $lackingAudio audio and $lackingVideo video lacking transceivers")

        repeat(lackingAudio) {
            pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO).direction =
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
        }

        repeat(lackingVideo) {
            pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).direction =
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
        }
    }

    suspend fun onSdpAnswer(
        sdp: String,
        midToTrackId: Map<String, String>
    ) {
        peerConnectionMutex.withLock {
            val pc = peerConnection ?: return

            val answer = SessionDescription(
                SessionDescription.Type.ANSWER,
                sdp
            )

            this@PeerConnectionManager.midToTrackId = midToTrackId

            pc.setRemoteDescription(answer).onSuccess {
                drainCandidates()
            }
        }
    }

    private fun midToTrackIdMapping(localTracks: List<LocalTrack>): Map<String, String> {
        val pc = peerConnection ?: return emptyMap()

        val mapping = mutableMapOf<String, String>()

        pc.transceivers.forEach {
            val trackId = it.sender.track()?.id() ?: return@forEach

            if (!localTracks.map { track -> track.id() }.contains(trackId)) return@forEach

            mapping[it.mid] = trackId
        }

        return mapping
    }

    data class SdpOffer(
        val description: String,
        val midToTrackIdMapping: Map<String, String>
    )

    suspend fun getSdpOffer(
        integratedTurnServers: List<OfferData.TurnServer>,
        tracksTypes: Map<String, Int>,
        localTracks: List<LocalTrack>
    ): SdpOffer {
        qrcMutex.withLock {
            this@PeerConnectionManager.queuedRemoteCandidates = mutableListOf()
        }
        prepareIceServers(integratedTurnServers)

        var needsRestart = true
        if (peerConnection == null) {
            setupPeerConnection(localTracks)
            needsRestart = false
        }
        peerConnectionMutex.withLock {
            val pc = peerConnection!!

            if (needsRestart) {
                pc.restartIce()
            }

            addNecessaryTransceivers(tracksTypes)

            pc.transceivers.forEach {
                if (it.direction == RtpTransceiver.RtpTransceiverDirection.SEND_RECV) {
                    it.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
                }
            }

            val constraints = MediaConstraints()

            Timber.i("Creating offer")
            val offer = pc.createOffer(constraints).getOrThrow()

            Timber.i("Setting local description")
            pc.setLocalDescription(offer).getOrThrow()

            return SdpOffer(offer.description, midToTrackIdMapping(localTracks))
        }
    }

    suspend fun setTrackEncoding(trackId: String, trackEncoding: TrackEncoding, enabled: Boolean) {
        peerConnectionMutex.withLock {
            val sender = peerConnection?.senders?.find { it -> it.track()?.id() == trackId } ?: run {
                Timber.e("setTrackEncoding: Invalid trackId $trackId, no track sender found")
                return
            }
            val params = sender.parameters
            val encoding = params?.encodings?.find { it.rid == trackEncoding.rid } ?: run {
                Timber.e("setTrackEncoding: Invalid encoding $trackEncoding, no such encoding found in peer connection")
                return
            }
            encoding.active = enabled
            sender.parameters = params
        }
    }

    suspend fun onRemoteCandidate(iceCandidate: IceCandidate) {
        peerConnectionMutex.withLock {
            val pc = peerConnection ?: return
            qrcMutex.withLock {
                if (this@PeerConnectionManager.queuedRemoteCandidates == null) {
                    pc.addIceCandidate(iceCandidate)
                } else {
                    this@PeerConnectionManager.queuedRemoteCandidates!!.add(iceCandidate)
                }
            }
        }
    }

    suspend fun close() {
        peerConnectionMutex.withLock {
            peerConnection?.close()
        }
    }

    override fun onSignalingChange(state: PeerConnection.SignalingState?) {
        Timber.d("Changed signalling state to $state")
    }

    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
        Timber.d("Changed ice connection state to $state")
    }

    override fun onIceConnectionReceivingChange(receiving: Boolean) {
        Timber.d("Changed ice connection receiving status to: $receiving")
    }

    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
        Timber.d("Change ice gathering state to $state")
    }

    override fun onIceCandidate(candidate: IceCandidate?) {
        if (candidate != null) {
            peerConnectionListener.onLocalIceCandidate(candidate)
        }
    }

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
        Timber.d("Removed ice candidates from connection")
    }

    override fun onAddStream(stream: MediaStream?) {
        Timber.d("Added media stream")
    }

    override fun onRemoveStream(stream: MediaStream?) {
        Timber.d("Removed media stream")
    }

    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
        var trackId: String? = null
        coroutineScope.launch {
            peerConnectionMutex.withLock {
                val pc = peerConnection ?: return@launch

                val transceiver = pc.transceivers.find {
                    it.receiver.id() == receiver?.id()
                } ?: return@launch

                val mid = transceiver.mid

                trackId = midToTrackId[mid] ?: run {
                    Timber.e("onAddTrack: Track with mid=$mid not found")
                    return@launch
                }
            }
            peerConnectionListener.onAddTrack(trackId!!, receiver!!.track()!!)
        }
    }

    override fun onRemoveTrack(receiver: RtpReceiver?) {
        super.onRemoveTrack(receiver)
    }

    override fun onDataChannel(dataChannel: DataChannel?) {
        Timber.d("New data channel")
    }

    override fun onRenegotiationNeeded() {
        Timber.d("Renegotiation needed")
    }

    fun getStats(): Map<String, RTCStats> {
        peerConnection?.getStats { rtcStatsReport -> extractRelevantStats(rtcStatsReport) }
        return peerConnectionStats.toMap()
    }

    private fun extractRelevantStats(rp: RTCStatsReport) {
        rp.statsMap.values.forEach {
            if (it.type == "outbound-rtp") {
                val durations = it.members["qualityLimitationDurations"] as? Map<*, *>
                val qualityLimitation = QualityLimitationDurations(
                    durations?.get("bandwidth") as? Double ?: 0.0,
                    durations?.get("cpu") as? Double ?: 0.0,
                    durations?.get("none") as? Double ?: 0.0,
                    durations?.get("other") as? Double ?: 0.0
                )

                val tmp = RTCOutboundStats(
                    it.members["kind"] as? String,
                    it.members["rid"] as? String,
                    it.members["bytesSent"] as? BigInteger,
                    it.members["targetBitrate"] as? Double,
                    it.members["packetsSent"] as? Long,
                    it.members["framesEncoded"] as? Long,
                    it.members["framesPerSecond"] as? Double,
                    it.members["frameWidth"] as? Long,
                    it.members["frameHeight"] as? Long,
                    qualityLimitation
                )

                peerConnectionStats[it.id as String] = tmp
            } else if (it.type == "inbound-rtp") {
                val tmp = RTCInboundStats(
                    it.members["kind"] as? String,
                    it.members["jitter"] as? Double,
                    it.members["packetsLost"] as? Int,
                    it.members["packetsReceived"] as? Long,
                    it.members["bytesReceived"] as? BigInteger,
                    it.members["framesReceived"] as? Int,
                    it.members["frameWidth"] as? Long,
                    it.members["frameHeight"] as? Long,
                    it.members["framesPerSecond"] as? Double,
                    it.members["framesDropped"] as? Long
                )

                peerConnectionStats[it.id as String] = tmp
            }
        }
    }
}

/**
 * Enforces `SEND_ONLY` direction in case of `SEND_RECV` transceivers.
 */
fun PeerConnection.enforceSendOnlyDirection() {
    this.transceivers.forEach {
        if (it.direction == RtpTransceiver.RtpTransceiverDirection.SEND_RECV) {
            it.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
        }
    }
}
