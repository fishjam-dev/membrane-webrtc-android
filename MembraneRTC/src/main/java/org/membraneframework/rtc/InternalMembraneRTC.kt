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
import org.membraneframework.rtc.transport.EventTransportError
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
    appContext: Context,
    rtcEngineCommunicationFactory: RTCEngineCommunication.RTCEngineCommunicationFactory
) : RTCEngineListener, PeerConnection.Observer {
    private val rtcEngineCommunication = rtcEngineCommunicationFactory.create(connectOptions, this)
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
            .setVideoEncoderFactory(
                SimulcastVideoEncoderFactoryWrapper(
                    eglBase.eglBaseContext,
                    connectOptions.encoderOptions
                )
            )
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
                rtcEngineCommunication.connect()
                listener.onConnected()
            } catch (e: Exception) {
                Timber.i(e, "Failed to connect")

                listener.onError(MembraneRTCError.Transport("Failed to connect"))
            }
        }
    }

    fun disconnect() {
        coroutineScope.launch {
            rtcEngineCommunication.disconnect()
            localTracks.forEach { it.stop() }
            peerConnection?.close()
        }
    }

    fun join() {
        coroutineScope.launch {
            rtcEngineCommunication.join(localPeer.metadata)
        }
    }

    fun createLocalVideoTrack(videoParameters: VideoParameters, metadata: Metadata = mapOf()): LocalVideoTrack {
        val videoTrack = LocalVideoTrack.create(
            context,
            peerConnectionFactory,
            eglBase,
            videoParameters
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
        val pc = peerConnection ?: run {
            Timber.e("addTrack: Peer connection not yet established")
            return
        }

        val videoParameters =
            (track as? LocalVideoTrack)?.videoParameters ?: (track as? LocalScreencastTrack)?.videoParameters
        val simulcastConfig = videoParameters?.simulcastConfig
        val sendEncodings =
            if (track.rtcTrack().kind() == "video" && simulcastConfig != null && simulcastConfig.enabled) {
                getSendEncodingsFromConfig(simulcastConfig)
            } else {
                listOf(RtpParameters.Encoding(null, true, null))
            }

        if (videoParameters?.maxBitrate != null) {
            applyBitrate(sendEncodings, videoParameters.maxBitrate)
        }

        val transceiverInit = RtpTransceiverInit(RtpTransceiverDirection.SEND_ONLY, streamIds, sendEncodings)
        pc.addTransceiver(track.rtcTrack(), transceiverInit)
    }

    private fun applyBitrate(encodings: List<RtpParameters.Encoding>, maxBitrate: TrackBandwidthLimit) {
        when (maxBitrate) {
            is TrackBandwidthLimit.BandwidthLimit -> splitBitrate(encodings, maxBitrate)
            is TrackBandwidthLimit.SimulcastBandwidthLimit ->
                encodings.forEach {
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

    fun setTrackBandwidth(trackId: String, bandwidthLimit: TrackBandwidthLimit.BandwidthLimit) {
        val pc = peerConnection ?: run {
            Timber.e("setTrackBandwidth: Peer connection not yet established")
            return
        }
        val sender = pc.senders.find { it.track()?.id() == trackId } ?: run {
            Timber.e("setTrackBandwidth: Invalid trackId: track sender not found")
            return
        }
        val params = sender.parameters

        applyBitrate(params.encodings, bandwidthLimit)

        sender.parameters = params
    }

    fun setEncodingBandwidth(trackId: String, encoding: String, bandwidthLimit: TrackBandwidthLimit.BandwidthLimit) {
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

    fun createScreencastTrack(
        mediaProjectionPermission: Intent,
        videoParameters: VideoParameters,
        metadata: Metadata = mapOf(),
        onEnd: () -> Unit
    ): LocalScreencastTrack? {
        val pc = peerConnection ?: run {
            Timber.e("createScreencastTrack: Peer connection not yet established")
            return null
        }
        val screencastTrack = LocalScreencastTrack.create(
            context,
            peerConnectionFactory,
            eglBase,
            mediaProjectionPermission,
            videoParameters
        ) { track ->
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
            rtcEngineCommunication.renegotiateTracks()
        }

        return screencastTrack
    }

    fun removeTrack(trackId: String): Boolean {
        val pc = peerConnection ?: run {
            Timber.e("removeTrack: Peer connection not yet established")
            return false
        }
        val track = localTracks.find { it.id() == trackId } ?: run {
            Timber.e("removeTrack: Can't find track to remove")
            return false
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
            rtcEngineCommunication.renegotiateTracks()
        }

        return true
    }

    private fun setupPeerConnection() {
        if (peerConnection != null) {
            Timber.e("setupPeerConnection: Peer connection already established!")
            return
        }

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
            rtcEngineCommunication.updatePeerMetadata(peerMetadata)
            localPeer = localPeer.copy(metadata = peerMetadata)
        }
    }

    fun updateTrackMetadata(trackId: String, trackMetadata: Metadata) {
        coroutineScope.launch {
            rtcEngineCommunication.updateTrackMetadata(trackId, trackMetadata)
            localPeer = localPeer.withTrack(trackId, trackMetadata)
        }
    }

    override fun onPeerAccepted(peerId: String, peersInRoom: List<Peer>) {
        this.localPeer = localPeer.copy(id = peerId)

        listener.onJoinSuccess(localPeer.id, peersInRoom = peersInRoom)

        peersInRoom.forEach {
            this.remotePeers[it.id] = it

            for ((trackId, metadata) in it.trackIdToMetadata) {
                val context = TrackContext(track = null, peer = it, trackId = trackId, metadata = metadata)

                this.trackContexts[trackId] = context

                this.listener.onTrackAdded(context)
            }
        }
    }

    override fun onPeerDenied() {
        // TODO: return meaningful data
        listener.onJoinError(mapOf<String, String>())
    }

    override fun onPeerJoined(peer: Peer) {
        if (peer.id == this.localPeer.id) {
            return
        }

        remotePeers[peer.id] = peer

        listener.onPeerJoined(peer)
    }

    override fun onPeerLeft(peerId: String) {
        val peer = remotePeers.remove(peerId) ?: run {
            Timber.e("Failed to process PeerLeft event: Peer not found: $peerId")
            return
        }

        val trackIds: List<String> = peer.trackIdToMetadata.keys.toList()

        trackIds.forEach {
            trackContexts.remove(it)?.let { ctx ->
                listener.onTrackRemoved(ctx)
            }
        }

        listener.onPeerLeft(peer)
    }

    override fun onPeerUpdated(peerId: String, peerMetadata: Metadata) {
        val peer = remotePeers.remove(peerId) ?: run {
            Timber.e("Failed to process PeerUpdated event: Peer not found: $peerId")
            return
        }

        remotePeers[peer.id] = peer.copy(metadata = peerMetadata)
    }

    override fun onOfferData(integratedTurnServers: List<OfferData.TurnServer>, tracksTypes: Map<String, Int>) {
        Timber.d("RAZ")
        coroutineScope.launch {
            Timber.d("DWA")
            qrcMutex.withLock {
                this@InternalMembraneRTC.queuedRemoteCandidates = mutableListOf()
            }
            prepareIceServers(integratedTurnServers)

            var needsRestart = true
            if (peerConnection == null) {
                setupPeerConnection()
                needsRestart = false
            }
            val pc = peerConnection!!

            if (needsRestart) {
                pc.restartIce()
            }

            addNecessaryTransceivers(tracksTypes)

            pc.transceivers.forEach {
                if (it.direction == RtpTransceiverDirection.SEND_RECV) {
                    it.direction = RtpTransceiverDirection.SEND_ONLY
                }
            }

            val constraints = MediaConstraints()

            try {
                Timber.d("TRZY")
                Timber.d("Creating offer")
                val offer = pc.createOffer(constraints).getOrThrow()

                Timber.d("Setting local description")
                pc.setLocalDescription(offer).getOrThrow()

                Timber.d("Sending an offer")
                rtcEngineCommunication.sdpOffer(
                    offer.description,
                    localPeer.trackIdToMetadata,
                    midToTrackIdMapping()
                )
                Timber.d("SZESC")
            } catch (e: Exception) {
                Timber.e(e, "Failed to create an sdp offer")
            }
        }
    }

    override fun onSdpAnswer(type: String, sdp: String, midToTrackId: Map<String, String>) {
        val pc = peerConnection ?: return

        coroutineScope.launch {
            val answer = SessionDescription(
                SessionDescription.Type.ANSWER,
                sdp
            )

            pc.setRemoteDescription(answer).onSuccess {
                drainCandidates()
                // temporary workaround, the backend doesn't add ~ in sdp answer
                localTracks.forEach { localTrack ->
                    if (localTrack.rtcTrack().kind() != "video") return@forEach
                    var config: SimulcastConfig? = null
                    if (localTrack is LocalVideoTrack) {
                        config = localTrack.videoParameters.simulcastConfig
                    } else if (localTrack is LocalScreencastTrack) {
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

    override fun onRemoteCandidate(candidate: String, sdpMLineIndex: Int, sdpMid: String?) {
        coroutineScope.launch {
            val pc = peerConnection ?: return@launch
            val iceCandidate = IceCandidate(
                sdpMid ?: "",
                sdpMLineIndex,
                candidate
            )

            qrcMutex.withLock {
                if (this@InternalMembraneRTC.queuedRemoteCandidates == null) {
                    pc.addIceCandidate(iceCandidate)
                } else {
                    this@InternalMembraneRTC.queuedRemoteCandidates!!.add(iceCandidate)
                }
            }
        }
    }

    override fun onTracksAdded(peerId: String, trackIdToMetadata: Map<String, Metadata>) {
        if (localPeer.id == peerId) return

        val peer = remotePeers.remove(peerId) ?: run {
            Timber.e("Failed to process TracksAdded event: Peer not found: $peerId")
            return
        }

        val updatedPeer = peer.copy(trackIdToMetadata = trackIdToMetadata)

        remotePeers[updatedPeer.id] = updatedPeer

        for ((trackId, metadata) in updatedPeer.trackIdToMetadata) {
            val context = TrackContext(track = null, peer = peer, trackId = trackId, metadata = metadata)

            this.trackContexts[trackId] = context

            this.listener.onTrackAdded(context)
        }
    }

    override fun onTracksRemoved(peerId: String, trackIds: List<String>) {
        val peer = remotePeers[peerId] ?: run {
            Timber.e("Failed to process TracksRemoved event: Peer not found: $peerId")
            return
        }

        trackIds.forEach {
            val context = trackContexts.remove(it) ?: return@forEach

            this.listener.onTrackRemoved(context)
        }

        val updatedPeer = trackIds.fold(peer) { acc, trackId ->
            acc.withoutTrack(trackId)
        }

        remotePeers[peerId] = updatedPeer
    }

    override fun onTrackUpdated(peerId: String, trackId: String, metadata: Metadata) {
        val peer = remotePeers[peerId] ?: run {
            Timber.e("Failed to process TrackUpdated event: Peer not found: $peerId")
            return
        }

        val context = trackContexts[trackId] ?: run {
            Timber.e("Failed to process TrackUpdated event: Track context not found: $trackId")
            return
        }

        val updatedContext = context.copy(metadata = metadata)
        trackContexts[trackId] = updatedContext

        val updatedPeer = peer
            .withoutTrack(trackId)
            .withTrack(trackId, metadata)

        remotePeers[peerId] = updatedPeer

        this.listener.onTrackUpdated(updatedContext)
    }

    override fun onError(error: EventTransportError) {
        listener.onError(MembraneRTCError.Transport(error.message ?: "unknown transport message"))
    }

    override fun onClose() {
        listener.onError(MembraneRTCError.Transport("transport has been closed"))
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

    private fun onLocalCandidate(localCandidate: IceCandidate) {
        coroutineScope.launch {
            rtcEngineCommunication.localCandidate(localCandidate.sdp, localCandidate.sdpMLineIndex)
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

            IceServer
                .builder(url)
                .setUsername(it.username)
                .setPassword(it.password)
                .createIceServer()
        }

        val config = RTCConfiguration(iceServers)
        config.iceTransportsType = IceTransportsType.RELAY
        this.config = config
    }

    private fun addNecessaryTransceivers(tracksTypes: Map<String, Int>) {
        val pc = peerConnection ?: return

        val necessaryAudio = tracksTypes["audio"] ?: 0
        val necessaryVideo = tracksTypes["video"] ?: 0

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

    fun setTargetTrackEncoding(trackId: String, encoding: TrackEncoding) {
        coroutineScope.launch {
            rtcEngineCommunication.setTargetTrackEncoding(trackId, encoding)
        }
    }

    private fun setTrackEncoding(trackId: String, trackEncoding: TrackEncoding, enabled: Boolean) {
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

        val transceiver = pc.transceivers.find {
            it.receiver.id() == receiver?.id()
        } ?: return

        val mid = transceiver.mid

        val trackId = midToTrackId[mid] ?: run {
            Timber.e("onAddTrack: Track with mid=$mid not found")
            return
        }
        val trackContext = trackContexts[trackId] ?: run {
            Timber.e("onAddTrack: Track context with trackId=$trackId not found")
            return
        }

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
