package org.membraneframework.rtc

import android.content.Context
import android.content.Intent
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.*
import org.membraneframework.rtc.events.*
import org.membraneframework.rtc.media.*
import org.membraneframework.rtc.models.Peer
import org.membraneframework.rtc.models.TrackContext
import org.membraneframework.rtc.transport.EventTransport
import org.membraneframework.rtc.transport.EventTransportError
import org.membraneframework.rtc.transport.EventTransportListener
import org.membraneframework.rtc.utils.ClosableCoroutineScope
import org.webrtc.*
import org.webrtc.PeerConnection.*
import org.webrtc.RtpTransceiver.RtpTransceiverDirection
import timber.log.Timber
import java.util.*
import org.membraneframework.rtc.utils.*
import org.webrtc.AudioTrack
import org.webrtc.VideoTrack

internal class InternalMembraneRTC
@AssistedInject
constructor(
    @Assisted
    private val connectOptions: ConnectOptions,
    @Assisted
    private val listener: MembraneRTCListener,
    @Assisted
    private val defaultDispatcher: CoroutineDispatcher,
    private val peerConnectionFactory: PeerConnectionFactory,
    private val eglBase: EglBase,
    private val context: Context
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

    // local media tracks
    var localVideoTrack: LocalVideoTrack? = null
        private set

    var localAudioTrack: LocalAudioTrack? = null
        private set

    var localScreencastTrack: LocalScreencastTrack? = null
    private var localScreencastSender: RtpSender? = null

    private var iceServers: List<IceServer>? = null
    private var config: RTCConfiguration? = null
    private var peerConnection: PeerConnection? = null


    private val coroutineScope: CoroutineScope =
        ClosableCoroutineScope(SupervisorJob() + defaultDispatcher)

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

                setupMediaTracks()

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
            localVideoTrack?.stop()
            localAudioTrack?.stop()
            peerConnection?.close()
        }
    }

    fun join() {
        coroutineScope.launch {
            transport.send(Join(localPeer.metadata))
        }
    }

    private fun setupMediaTracks() {
        this.localVideoTrack = LocalVideoTrack.create(
            context,
            peerConnectionFactory,
            eglBase
        )
        this.localAudioTrack = LocalAudioTrack.create(context, peerConnectionFactory)

        this.localVideoTrack?.start()
        this.localAudioTrack?.start()

        val displayName = localPeer.metadata["displayName"] ?: ""

        val trackMetadata = mapOf("user_id" to displayName)

        this.localPeer = localPeer.copy(
            trackIdToMetadata = mapOf(
                localVideoTrack!!.rtcTrack().id() to trackMetadata,
                localAudioTrack!!.rtcTrack().id() to trackMetadata
            )
        )

        Timber.d("Tracks metadata ${localPeer.trackIdToMetadata}")
    }


    fun startScreencast(mediaProjectionPermission: Intent, onEnd: () -> Unit) {
        val pc = peerConnection ?: return

        val track = LocalScreencastTrack.create(context, peerConnectionFactory, eglBase, mediaProjectionPermission) {
            onEnd()

            stopScreencast()
        }

        coroutineScope.launch {
            track.startForegroundService(null, null)
            track.start()
        }

        this.localScreencastTrack = track

        this.localScreencastSender = pc.addTrack(track.rtcTrack(), listOf(UUID.randomUUID().toString()))

        pc.transceivers.forEach {
            if (it.direction == RtpTransceiverDirection.SEND_RECV) {
                it.direction = RtpTransceiverDirection.SEND_ONLY
            }
        }

        coroutineScope.launch {
            transport.send(RenegotiateTracks())
        }

        val trackIdToMetadata = localPeer.trackIdToMetadata.toMutableMap()
        trackIdToMetadata[track.id()] = mapOf(
            "type" to "screensharing",
            "user_id" to (localPeer.metadata["displayName"] ?: "")
        )

        this.localPeer = localPeer.copy(
            trackIdToMetadata = trackIdToMetadata
        )
    }

    fun stopScreencast() {
        val pc = peerConnection ?: return
        val track = localScreencastTrack ?: return
        val sender = localScreencastSender ?: return

        val trackId = track.id()
        track.stop()
        pc.removeTrack(sender)

        val trackIdToMetadata = localPeer.trackIdToMetadata.toMutableMap()
        trackIdToMetadata.remove(trackId)
        this.localPeer = localPeer.copy(trackIdToMetadata = trackIdToMetadata)

        coroutineScope.launch {
            transport.send(RenegotiateTracks())
        }
    }

    private fun defaultStunServer(): IceServer {
        return IceServer
            .builder("stun:stun.l.google.com:19302")
            .createIceServer()
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

        val streamIds = listOf(UUID.randomUUID().toString())

        localVideoTrack?.let {
            pc.addTrack(it.rtcTrack(), streamIds)
        }

        localAudioTrack?.let {
            pc.addTrack(it.rtcTrack(), streamIds)
        }

        // servers only accepts SEND_ONLY therefore switch all SEND_RECV directions
        pc.transceivers.forEach {
            if (it.direction == RtpTransceiverDirection.SEND_RECV) {
                it.direction = RtpTransceiverDirection.SEND_ONLY
            }
        }

        this.peerConnection = pc
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
            }

            is TrackUpdated -> {
                val peer = remotePeers[event.data.peerId] ?: return

                val context = trackContexts[event.data.trackId] ?: return

                val updatedContext = context.copy(metadata = event.data.metadata)
                trackContexts[event.data.trackId] = updatedContext

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
        prepareIceServers(offerData.data.integratedTurnServers, offerData.data.iceTransportPolicy)

        var needsRestart: Boolean = true
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
            if (it.direction == RtpTransceiver.RtpTransceiverDirection.SEND_RECV) {
                it.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
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

            pc.setRemoteDescription(answer)
        }
    }

    private fun onRemoteCandidate(remoteCandidate: RemoteCandidate) {
        val pc = peerConnection ?: return
        val candidate = IceCandidate(
            remoteCandidate.data.sdpMid ?: "",
            remoteCandidate.data.sdpMLineIndex,
            remoteCandidate.data.candidate
        )

         pc.addIceCandidate(candidate)
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

    private fun prepareIceServers(integratedTurnServers: List<OfferData.TurnServer>, iceTransportPolicy: String) {
        // config or ice servers are already initialized, skip the preparation
        if (config != null || iceServers != null) {
            return
        }

        // if integrated
        if (integratedTurnServers.isEmpty()) {
            config = RTCConfiguration(listOf(defaultStunServer()))
            config!!.iceTransportsType = IceTransportsType.ALL

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

        when (iceTransportPolicy) {
            "all" ->
                config.iceTransportsType = IceTransportsType.ALL
            "relay" ->
                config.iceTransportsType = IceTransportsType.RELAY
        }

        this.config = config
    }

    private fun addNecessaryTransceivers(offerData: OfferData) {
        val pc = peerConnection ?: return

        val necessaryAudio = offerData.data.tracksTypes["audio"] ?: 0
        val necessaryVideo = offerData.data.tracksTypes["video"] ?: 0

        var lackingAudio = necessaryAudio
        var lackingVideo = necessaryVideo

        pc.transceivers.filter {
            it.direction == RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
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
            pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO).let {
                it.direction = RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            }
        }

        repeat(lackingVideo) {
            pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).let {
                it.direction = RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            }
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

    // PeerConnection callbacks
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
        val cand = candidate ?: return

        onLocalCandidate(cand)

        Timber.d("Generated new ice candidate")
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
