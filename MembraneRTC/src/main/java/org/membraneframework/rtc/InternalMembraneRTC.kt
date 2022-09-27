package org.membraneframework.rtc

import android.content.Context
import android.content.Intent
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.membraneframework.rtc.events.*
import org.membraneframework.rtc.media.*
import org.membraneframework.rtc.models.Peer
import org.membraneframework.rtc.models.TrackContext
import org.membraneframework.rtc.transport.EventTransport
import org.membraneframework.rtc.transport.EventTransportError
import org.membraneframework.rtc.transport.EventTransportListener
import org.membraneframework.rtc.utils.*
import org.webrtc.*
import org.webrtc.AudioTrack
import org.webrtc.PeerConnection.*
import org.webrtc.RtpTransceiver.RtpTransceiverDirection
import org.webrtc.RtpTransceiver.RtpTransceiverInit
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import timber.log.Timber
import java.util.*

import org.membraneframework.rtc.utils.Metadata
import kotlin.collections.HashMap
import kotlin.math.pow

internal class InternalMembraneRTC
@AssistedInject
constructor(
    @Assisted
    private val connectOptions: ConnectOptions,
    @Assisted
    private val listener: MembraneRTCListener,
    @Assisted
    private val defaultDispatcher: CoroutineDispatcher,
    audioDeviceModule: AudioDeviceModule,
    private val eglBase: EglBase,
    private val context: Context,
    appContext: Context
) : EventTransportListener, PeerConnection.Observer {
    private var transport: EventTransport = connectOptions.transport

    private var localPeer: Peer =
        Peer(id = "", metadata = connectOptions.config, trackIdToMetadata = mapOf())

    // mapping from peer's id to the peer himself
    private val remotePeers = HashMap<String, Peer>()

    // mapping from remote track's id to its context
    private val trackContexts = HashMap<String, TrackContext>()

    // mapping from transceiver's mid to its remote track id
    private var midToTrackId: Map<String, String> = HashMap<String, String>()

    private val localTracks = mutableListOf<LocalTrack>()

    private var iceServers: List<IceServer>? = null
    private var config: RTCConfiguration? = null
    private var peerConnection: PeerConnection? = null
    private var queuedRemoteCandidates: MutableList<IceCandidate>? = null
    private val qrcMutex = Mutex()


    private val coroutineScope: CoroutineScope =
        ClosableCoroutineScope(SupervisorJob() + defaultDispatcher)

    private val peerConnectionFactory: PeerConnectionFactory

    init {

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(appContext)
                .createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(SimulcastVideoEncoderFactoryWrapper(
                eglBase.eglBaseContext,
                connectOptions.encoderOptions
            ))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            connectOptions: ConnectOptions,
            listener: MembraneRTCListener,
            defaultDispatcher: CoroutineDispatcher
        ): InternalMembraneRTC
    }

    fun connect() {
        coroutineScope.launch {
            try {
                transport.connect(this@InternalMembraneRTC)

                listener.onConnected()
            } catch (e: Exception) {
                Timber.i(e, "Failed to connect")

                listener.onError(MembraneRTCError.Transport("Failed to connect"))
            }
        }
    }

    fun disconnect() {
        coroutineScope.launch {
            transport.disconnect()
            localTracks.forEach { it.stop() }
            peerConnection?.close()
        }
    }

    fun join() {

        coroutineScope.launch {
            transport.send(Join(localPeer.metadata))
        }
    }

    fun createLocalVideoTrack(
        videoParameters: VideoParameters,
        metadata: Metadata = mapOf(),
    ): LocalVideoTrack {
        val videoTrack = LocalVideoTrack.create(
            context,
            peerConnectionFactory,
            eglBase,
            videoParameters,
        ).also {
            it.start()
        }

        localTracks.add(videoTrack)
        localPeer = localPeer.withTrack(videoTrack.id(), metadata)

        return videoTrack
    }

    fun createLocalAudioTrack(metadata: Metadata = mapOf()): LocalAudioTrack {
        val audioTrack = LocalAudioTrack.create(context, peerConnectionFactory).also {
            it.start()
        }

        localTracks.add(audioTrack)
        localPeer = localPeer.withTrack(audioTrack.id(), metadata)

        return audioTrack
    }

    private fun getSendEncodingsFromConfig(simulcastConfig: SimulcastConfig): List<RtpParameters.Encoding> {
        val sendEncodings = Constants.simulcastEncodings()
        simulcastConfig.activeEncodings.forEach {
            sendEncodings[it.ordinal].active = true
        }
        return sendEncodings
    }

    private fun addTrack(track: LocalTrack, streamIds: List<String>) {
        val pc = peerConnection ?: return

        val videoParameters = (track as? LocalVideoTrack)?.videoParameters ?: (track as? LocalScreencastTrack)?.videoParameters
        val simulcastConfig = videoParameters?.simulcastConfig
        val sendEncodings = if(track.rtcTrack().kind() == "video" && simulcastConfig != null && simulcastConfig.enabled) {
            getSendEncodingsFromConfig(simulcastConfig)
        } else {
            listOf(RtpParameters.Encoding(null, true, null))
        }

        if(videoParameters?.maxBitrate != null) {
            applyBitrate(sendEncodings, videoParameters.maxBitrate)
        }

        val transceiverInit = RtpTransceiverInit(RtpTransceiverDirection.SEND_ONLY, streamIds, sendEncodings)
        pc.addTransceiver(track.rtcTrack(), transceiverInit)
    }

    private fun applyBitrate(encodings: List<RtpParameters.Encoding>, maxBitrate: TrackBandwidthLimit) {
        when(maxBitrate) {
            is TrackBandwidthLimit.BandwidthLimit -> splitBitrate(encodings, maxBitrate)
            is TrackBandwidthLimit.SimulcastBandwidthLimit ->
                encodings.forEach {
                    val encodingLimit = maxBitrate.limit[it.rid]?.limit ?: 0
                    it.maxBitrateBps = if (encodingLimit == 0) null else encodingLimit * 1024
                }
        }
    }

    private fun splitBitrate(encodings: List<RtpParameters.Encoding>, maxBitrate: TrackBandwidthLimit.BandwidthLimit) {
        if(encodings.isEmpty()) return
        if(maxBitrate.limit == 0) {
            encodings.forEach { it.maxBitrateBps = null }
            return
        }

        val k0 = encodings.minByOrNull { it.scaleResolutionDownBy ?: 1.0 }

        val bitrateParts = encodings.sumOf { ((k0?.scaleResolutionDownBy ?: 1.0) / (it.scaleResolutionDownBy ?: 1.0)).pow(2) }

        val x = maxBitrate.limit / bitrateParts

        encodings.forEach {
            it.maxBitrateBps = (x * ((k0?.scaleResolutionDownBy ?: 1.0)/(it.scaleResolutionDownBy ?: 1.0)).pow(2) * 1024).toInt()
        }
    }

    fun setTrackBandwidth(trackId: String, bandwidthLimit: TrackBandwidthLimit.BandwidthLimit) {
        val pc = peerConnection ?: return
        val sender = pc.senders.find { it.track()?.id() == trackId} ?: return
        val params = sender.parameters

        applyBitrate(params.encodings, bandwidthLimit)

        sender.parameters = params
    }

    fun setEncodingBandwidth(trackId: String, encoding: String, bandwidthLimit: TrackBandwidthLimit.BandwidthLimit) {
        val pc = peerConnection ?: return
        val sender = pc.senders.find { it.track()?.id() == trackId} ?: return

        val params = sender.parameters
        val encodingParameters = params.encodings.find { it.rid == encoding } ?: return

        encodingParameters.maxBitrateBps = bandwidthLimit.limit * 1024

        sender.parameters = params
    }


    fun createScreencastTrack(
        mediaProjectionPermission: Intent,
        videoParameters: VideoParameters,
        metadata: Metadata = mapOf(),
        onEnd: () -> Unit
    ): LocalScreencastTrack? {
        val pc = peerConnection ?: return null

        val screencastTrack = LocalScreencastTrack.create(context, peerConnectionFactory, eglBase, mediaProjectionPermission, videoParameters) { track ->
            onEnd()

            removeTrack(track.id())
        }

        localTracks.add(screencastTrack)
        localPeer = localPeer.withTrack(screencastTrack.id(), metadata)

        coroutineScope.launch {
            screencastTrack.startForegroundService(null, null)
            screencastTrack.start()
        }

        val streamIds = listOf(UUID.randomUUID().toString())

        addTrack(screencastTrack, streamIds)

        pc.enforceSendOnlyDirection()

        coroutineScope.launch {
            transport.send(RenegotiateTracks())
        }

        return screencastTrack
    }

    fun removeTrack(trackId: String): Boolean {
        val pc = peerConnection ?: return false
        val track = localTracks.find { it.id() == trackId } ?: run {
            return@removeTrack false
        }

        // remove a sender that is associated with given track
        val rtcTrack = track.rtcTrack()
        pc.transceivers.find { it.sender.track()?.id() == rtcTrack.id() }?.sender?.let {
            pc.removeTrack(it)
        }

        localTracks.remove(track)
        localPeer = localPeer.withoutTrack(trackId)
        track.stop()

        coroutineScope.launch {
            transport.send(RenegotiateTracks())
        }

        return true
    }

    private fun setupPeerConnection() {
        if (peerConnection != null) return

        assert(config != null)
        val config = this.config!!

        config.sdpSemantics = SdpSemantics.UNIFIED_PLAN
        config.continualGatheringPolicy = ContinualGatheringPolicy.GATHER_CONTINUALLY
        config.candidateNetworkPolicy = CandidateNetworkPolicy.ALL
        config.disableIpv6 = true
        config.tcpCandidatePolicy = TcpCandidatePolicy.DISABLED

        val pc = peerConnectionFactory.createPeerConnection(config, this)
            ?: throw IllegalStateException("Failed to create a peerConnection")

        this.peerConnection = pc

        val streamIds = listOf(UUID.randomUUID().toString())

        localTracks.forEach {
            addTrack(it, streamIds)
        }

        pc.enforceSendOnlyDirection()
        
    }

    fun updatePeerMetadata(peerMetadata: Metadata) {
        coroutineScope.launch {
            transport.send(UpdatePeerMetadata(peerMetadata))
            localPeer = localPeer.copy(metadata = peerMetadata)
        }
    }

    fun updateTrackMetadata(trackId: String, trackMetadata: Metadata) {
        coroutineScope.launch {
            transport.send(UpdateTrackMetadata(trackId, trackMetadata))
            localPeer = localPeer.withTrack(trackId, trackMetadata)
        }
    }

    override fun onEvent(event: ReceivableEvent) {
        when (event) {
            is PeerAccepted -> {
                this.localPeer = localPeer.copy(id = event.data.id)

                val peers = event.data.peersInRoom

                listener.onJoinSuccess(localPeer.id, peersInRoom = peers)

                peers.forEach {
                    this.remotePeers[it.id] = it

                    for ((trackId, metadata) in it.trackIdToMetadata) {
                        val context = TrackContext(track = null, peer = it, trackId = trackId, metadata = metadata)

                        this.trackContexts[trackId] = context

                        this.listener.onTrackAdded(context)
                    }
                }
            }

            is PeerDenied -> {
                // TODO: return meaningful data
                listener.onJoinError(mapOf<String, String>())
            }

            is PeerJoined -> {
                val peer = event.data.peer
                if (peer.id == this.localPeer.id) {
                    return
                }

                remotePeers[peer.id] = peer

                listener.onPeerJoined(peer)
            }

            is PeerLeft -> {
                val peer = remotePeers.remove(event.data.peerId) ?: return

                val trackIds: List<String> = peer.trackIdToMetadata.keys.toList()

                trackIds.forEach {
                    trackContexts.remove(it)?.let { ctx ->
                        listener.onTrackRemoved(ctx)
                    }
                }

                listener.onPeerLeft(peer)
            }

            is PeerUpdated -> {
                val peer = remotePeers.remove(event.data.peerId) ?: return

                remotePeers[peer.id] = peer.copy(metadata = event.data.metadata)
            }

            is OfferData -> {
                coroutineScope.launch {
                    onOfferData(event)
                }
            }

            is SdpAnswer -> {
                coroutineScope.launch {
                    onSdpAnswer(event)
                }
            }

            is RemoteCandidate -> {
                coroutineScope.launch {
                    onRemoteCandidate(event)
                }
            }

            is TracksAdded -> {
                if (localPeer.id == event.data.peerId) return

                val peer = remotePeers.remove(event.data.peerId) ?: return

                val updatedPeer = peer.copy(trackIdToMetadata = event.data.trackIdToMetadata)

                remotePeers[updatedPeer.id] = updatedPeer

                for ((trackId, metadata) in updatedPeer.trackIdToMetadata) {
                    val context = TrackContext(track = null, peer = peer, trackId = trackId, metadata = metadata)

                    this.trackContexts[trackId] = context

                    this.listener.onTrackAdded(context)
                }
            }

            is TracksRemoved -> {
                val peer = remotePeers[event.data.peerId] ?: return

                event.data.trackIds.forEach {
                    val context = trackContexts.remove(it) ?: return@forEach

                    this.listener.onTrackRemoved(context)
                }

                val updatedPeer = event.data.trackIds.fold(peer) { acc, trackId ->
                    acc.withoutTrack(trackId)
                }

                remotePeers[event.data.peerId] = updatedPeer
            }

            is TrackUpdated -> {
                val peer = remotePeers[event.data.peerId] ?: return

                val context = trackContexts[event.data.trackId] ?: return

                val updatedContext = context.copy(metadata = event.data.metadata)
                trackContexts[event.data.trackId] = updatedContext

                val updatedPeer = peer
                    .withoutTrack(event.data.trackId)
                    .withTrack(event.data.trackId, event.data.metadata)

                remotePeers[event.data.peerId] = updatedPeer

                this.listener.onTrackUpdated(updatedContext)
            }

            else ->
                Timber.e("Failed to process unknown event: $event")
        }

    }

    override fun onError(error: EventTransportError) {
        listener.onError(MembraneRTCError.Transport(error.message ?: "unknown transport message"))
    }

    override fun onClose() {
        listener.onError(MembraneRTCError.Transport("transport has been closed"))
    }

    private suspend fun onOfferData(offerData: OfferData) {
        qrcMutex.withLock {
            this.queuedRemoteCandidates = mutableListOf()
        }
        prepareIceServers(offerData.data.integratedTurnServers)

        var needsRestart = true
        if (peerConnection == null) {
            setupPeerConnection()
            needsRestart = false
        }
        val pc = peerConnection!!

        if (needsRestart) {
            pc.restartIce()
        }

        addNecessaryTransceivers(offerData)

        pc.transceivers.forEach {
            if (it.direction == RtpTransceiverDirection.SEND_RECV) {
                it.direction = RtpTransceiverDirection.SEND_ONLY
            }
        }

        coroutineScope.launch {
            val constraints = MediaConstraints()

            try {
                Timber.i("Creating offer")
                val offer = pc.createOffer(constraints).getOrThrow()

                Timber.i("Setting local description")
                pc.setLocalDescription(offer).getOrThrow()


                Timber.i("Sending an offer")
                transport.send(
                    SdpOffer(
                        offer.description,
                        localPeer.trackIdToMetadata,
                        midToTrackIdMapping()
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to create an sdp offer")
            }
        }
    }


    private suspend fun onSdpAnswer(sdpAnswer: SdpAnswer) {
        val pc = peerConnection ?: return

        coroutineScope.launch {
            val answer = SessionDescription(
                SessionDescription.Type.ANSWER,
                sdpAnswer.data.sdp
            )

            midToTrackId = sdpAnswer.data.midToTrackId

            pc.setRemoteDescription(answer).onSuccess {
                drainCandidates()
                // temporary workaround, the backend doesn't add ~ in sdp answer
                localTracks.forEach { localTrack ->
                    if(localTrack.rtcTrack().kind() != "video") return@forEach
                    var config: SimulcastConfig? = null
                    if(localTrack is LocalVideoTrack) {
                        config = localTrack.videoParameters.simulcastConfig
                    } else if(localTrack is LocalScreencastTrack) {
                        config = localTrack.videoParameters.simulcastConfig
                    }
                    listOf(TrackEncoding.L, TrackEncoding.M, TrackEncoding.H)
                        .forEach {
                            if (config?.activeEncodings?.contains(it) == false) {
                                disableTrackEncoding(localTrack.id(), it)
                            }
                        }
                }

            }
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

    private suspend fun onRemoteCandidate(remoteCandidate: RemoteCandidate) {
        val pc = peerConnection ?: return
        val candidate = IceCandidate(
            remoteCandidate.data.sdpMid ?: "",
            remoteCandidate.data.sdpMLineIndex,
            remoteCandidate.data.candidate
        )

        qrcMutex.withLock {
            if (this.queuedRemoteCandidates == null) {
                pc.addIceCandidate(candidate)
            } else {
                this.queuedRemoteCandidates!!.add(candidate)
            }
        }
    }

    private fun onLocalCandidate(localCandidate: IceCandidate) {
        coroutineScope.launch {
            transport.send(
                LocalCandidate(
                    localCandidate.sdp,
                    localCandidate.sdpMLineIndex
                )
            )
        }
    }

    private fun prepareIceServers(integratedTurnServers: List<OfferData.TurnServer>) {
        // config or ice servers are already initialized, skip the preparation
        if (config != null || iceServers != null) {
            return
        }

        this.iceServers = integratedTurnServers.map {
            val url = listOf(
                "turn", ":",
                it.serverAddr, ":",
                it.serverPort.toString(), "?transport=", it.transport
            ).joinToString("")

            IceServer
                .builder(url)
                .setUsername(it.username)
                .setPassword(it.password)
                .createIceServer()
        }

        val config =  RTCConfiguration(iceServers)
        config.iceTransportsType = IceTransportsType.RELAY
        this.config = config
    }

    private fun addNecessaryTransceivers(offerData: OfferData) {
        val pc = peerConnection ?: return

        val necessaryAudio = offerData.data.tracksTypes["audio"] ?: 0
        val necessaryVideo = offerData.data.tracksTypes["video"] ?: 0

        var lackingAudio = necessaryAudio
        var lackingVideo = necessaryVideo

        pc.transceivers.filter {
            it.direction == RtpTransceiverDirection.RECV_ONLY
        }.forEach {
            val track = it.receiver.track() ?: return@forEach

            when (track.kind()) {
                "audio" ->
                    lackingAudio -= 1
                "video" ->
                    lackingVideo -= 1
            }
        }

        Timber.d("peerConnection adding $lackingAudio audio and $lackingVideo video lacking transceivers")

        repeat(lackingAudio) {
            pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO).direction =
                RtpTransceiverDirection.RECV_ONLY
        }

        repeat(lackingVideo) {
            pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).direction =
                RtpTransceiverDirection.RECV_ONLY
        }
    }

    private fun midToTrackIdMapping(): Map<String, String> {
        val pc = peerConnection ?: return emptyMap()

        val mapping = mutableMapOf<String, String>()

        val localTrackKeys = localPeer.trackIdToMetadata.keys

        pc.transceivers.forEach {
            val trackId = it.sender.track()?.id() ?: return@forEach

            if (!localTrackKeys.contains(trackId)) return@forEach

            mapping[it.mid] = trackId
        }

        return mapping
    }

    fun selectTrackEncoding(peerId: String, trackId: String, encoding: TrackEncoding) {
        coroutineScope.launch {
            transport.send(
                SelectEncoding(
                    peerId,
                    trackId,
                    encoding.rid
                )
            )
        }
    }

    private fun setTrackEncoding(trackId: String, trackEncoding: TrackEncoding, enabled: Boolean) {
        val sender = peerConnection?.senders?.find { it -> it.track()?.id() == trackId}
        val params = sender?.parameters
        val encoding = params?.encodings?.find { it.rid == trackEncoding.rid }
        encoding?.active = enabled
        sender?.parameters = params
    }

    fun enableTrackEncoding(trackId: String, encoding: TrackEncoding) {
        setTrackEncoding(trackId, encoding, true)
    }

    fun disableTrackEncoding(trackId: String, encoding: TrackEncoding) {
        setTrackEncoding(trackId, encoding, false)
    }

    // PeerConnection callbacks
    override fun onSignalingChange(state: SignalingState?) {
        Timber.d("Changed signalling state to $state")
    }

    override fun onIceConnectionChange(state: IceConnectionState?) {
        Timber.d("Changed ice connection state to $state")
    }

    override fun onIceConnectionReceivingChange(receiving: Boolean) {
        Timber.d("Changed ice connection receiving status to: $receiving")
    }

    override fun onIceGatheringChange(state: IceGatheringState?) {
        Timber.d("Change ice gathering state to $state")
    }

    override fun onIceCandidate(candidate: IceCandidate?) {
        candidate?.let {
            onLocalCandidate(it)
            Timber.d("Generated new ice candidate")
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
        val pc = peerConnection ?: return

        val transceivers = pc.transceivers
        Timber.i("${transceivers.map { it.receiver.id() }.toList()}")

        val transceiver = pc.transceivers.find {
            it.receiver.id() == receiver?.id()
        } ?: return

        val mid = transceiver.mid

        val trackId = midToTrackId[mid] ?: return // throw IllegalStateException("onAddTrack track has not been found")
        val trackContext = trackContexts[trackId] ?: return // throw IllegalStateException("onAddTrack track context has not been found")

        val newTrackContext = when (val track = receiver!!.track()) {
            is VideoTrack ->
                trackContext.copy(track = RemoteVideoTrack(track, eglBase))

            is AudioTrack ->
                trackContext.copy(track = RemoteAudioTrack(track))

            else ->
                throw IllegalStateException("onAddTrack invalid type of incoming track")
        }

        trackContexts[trackId] = newTrackContext

        listener.onTrackReady(newTrackContext)
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
}


/**
 * Enforces `SEND_ONLY` direction in case of `SEND_RECV` transceivers.
 */
fun PeerConnection.enforceSendOnlyDirection() {
    this.transceivers.forEach {
        if (it.direction == RtpTransceiverDirection.SEND_RECV) {
            it.direction = RtpTransceiverDirection.SEND_ONLY
        }
    }
}
