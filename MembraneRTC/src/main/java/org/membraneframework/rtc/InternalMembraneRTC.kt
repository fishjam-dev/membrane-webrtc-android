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
import org.membraneframework.rtc.events.OfferData
import org.membraneframework.rtc.media.*
import org.membraneframework.rtc.models.*
import org.membraneframework.rtc.transport.EventTransportError
import org.membraneframework.rtc.utils.ClosableCoroutineScope
import org.membraneframework.rtc.utils.Metadata
import org.membraneframework.rtc.utils.TimberDebugTree
import org.webrtc.*
import org.webrtc.AudioTrack
import org.webrtc.VideoTrack
import timber.log.Timber
import java.util.*

internal class InternalMembraneRTC
@AssistedInject
constructor(
    @Assisted
    private val connectOptions: ConnectOptions,
    @Assisted
    private val listener: MembraneRTCListener,
    @Assisted
    private val defaultDispatcher: CoroutineDispatcher,
    private val eglBase: EglBase,
    private val context: Context,
    rtcEngineCommunicationFactory: RTCEngineCommunication.RTCEngineCommunicationFactory,
    peerConnectionManagerFactory: PeerConnectionManager.PeerConnectionManagerFactory,
    peerConnectionFactoryWrapperFactory: PeerConnectionFactoryWrapper.PeerConnectionFactoryWrapperFactory
) : RTCEngineListener, PeerConnectionListener {
    private val rtcEngineCommunication = rtcEngineCommunicationFactory.create(connectOptions.transport, this)
    private val peerConnectionFactoryWrapper = peerConnectionFactoryWrapperFactory.create(connectOptions)
    private val peerConnectionManager = peerConnectionManagerFactory.create(this, peerConnectionFactoryWrapper)

    private var localPeer: Peer =
        Peer(id = "", metadata = connectOptions.config, trackIdToMetadata = mapOf())

    // mapping from peer's id to the peer himself
    private val remotePeers = HashMap<String, Peer>()

    // mapping from remote track's id to its context
    private val trackContexts = HashMap<String, TrackContext>()

    private val localTracks = mutableListOf<LocalTrack>()

    private val coroutineScope: CoroutineScope =
        ClosableCoroutineScope(SupervisorJob() + defaultDispatcher)

    init {
        if (BuildConfig.DEBUG) {
            Timber.plant(TimberDebugTree())
        }
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
            peerConnectionManager.close()
        }
    }

    fun join() {
        coroutineScope.launch {
            rtcEngineCommunication.join(localPeer.metadata)
        }
    }

    fun createLocalVideoTrack(
        videoParameters: VideoParameters,
        metadata: Metadata = mapOf(),
        captureDeviceName: String? = null
    ): LocalVideoTrack {
        val videoTrack = LocalVideoTrack.create(
            context,
            peerConnectionFactoryWrapper.peerConnectionFactory,
            eglBase,
            videoParameters,
            captureDeviceName
        ).also {
            it.start()
        }

        localTracks.add(videoTrack)
        localPeer = localPeer.withTrack(videoTrack.id(), metadata)

        return videoTrack
    }

    fun createLocalAudioTrack(metadata: Metadata = mapOf()): LocalAudioTrack {
        val audioTrack = LocalAudioTrack.create(context, peerConnectionFactoryWrapper.peerConnectionFactory).also {
            it.start()
        }

        localTracks.add(audioTrack)
        localPeer = localPeer.withTrack(audioTrack.id(), metadata)

        return audioTrack
    }

    fun setTrackBandwidth(trackId: String, bandwidthLimit: TrackBandwidthLimit.BandwidthLimit) {
        peerConnectionManager.setTrackBandwidth(trackId, bandwidthLimit)
    }

    fun setEncodingBandwidth(trackId: String, encoding: String, bandwidthLimit: TrackBandwidthLimit.BandwidthLimit) {
        peerConnectionManager.setEncodingBandwidth(trackId, encoding, bandwidthLimit)
    }

    fun createScreencastTrack(
        mediaProjectionPermission: Intent,
        videoParameters: VideoParameters,
        metadata: Metadata = mapOf(),
        onEnd: () -> Unit
    ): LocalScreencastTrack {
        val screencastTrack = LocalScreencastTrack.create(
            context,
            peerConnectionFactoryWrapper.peerConnectionFactory,
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

        peerConnectionManager.addTrack(screencastTrack, streamIds)

        coroutineScope.launch {
            rtcEngineCommunication.renegotiateTracks()
        }

        return screencastTrack
    }

    fun removeTrack(trackId: String): Boolean {
        val track = localTracks.find { it.id() == trackId } ?: run {
            Timber.e("removeTrack: Can't find track to remove")
            return false
        }

        peerConnectionManager.removeTrack(track.id())

        localTracks.remove(track)
        localPeer = localPeer.withoutTrack(trackId)
        track.stop()

        coroutineScope.launch {
            rtcEngineCommunication.renegotiateTracks()
        }

        return true
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
        coroutineScope.launch {
            try {
                val offer = peerConnectionManager.getSdpOffer(integratedTurnServers, tracksTypes, localTracks)
                rtcEngineCommunication.sdpOffer(
                    offer.description,
                    localPeer.trackIdToMetadata,
                    offer.midToTrackIdMapping
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to create an sdp offer")
            }
        }
    }

    override fun onSdpAnswer(type: String, sdp: String, midToTrackId: Map<String, String>) {
        coroutineScope.launch {
            peerConnectionManager.onSdpAnswer(sdp, midToTrackId, localTracks)
        }
    }

    override fun onRemoteCandidate(candidate: String, sdpMLineIndex: Int, sdpMid: String?) {
        coroutineScope.launch {
            val iceCandidate = IceCandidate(
                sdpMid ?: "",
                sdpMLineIndex,
                candidate
            )

            peerConnectionManager.onRemoteCandidate(iceCandidate)
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

        context.metadata = metadata

        val updatedPeer = peer
            .withoutTrack(trackId)
            .withTrack(trackId, metadata)

        remotePeers[peerId] = updatedPeer

        this.listener.onTrackUpdated(context)
    }

    override fun onTrackEncodingChanged(peerId: String, trackId: String, encoding: String, encodingReason: String) {
        val encodingReasonEnum = EncodingReason.fromString(encodingReason)
        if (encodingReasonEnum == null) {
            Timber.e("Invalid encoding reason: $encodingReason")
            return
        }
        val trackContext = trackContexts[trackId]
        if (trackContext == null) {
            Timber.e("Invalid trackId: $trackId")
            return
        }
        val encodingEnum = TrackEncoding.fromString(encoding)
        if (encodingEnum == null) {
            Timber.e("Invalid encoding: $encoding")
            return
        }
        trackContext.setEncoding(encodingEnum, encodingReasonEnum)
        this.listener.onTrackEncodingChanged(peerId, trackId, encoding)
    }

    override fun onRemoved(peerId: String, reason: String) {
        if (peerId != localPeer.id) {
            Timber.e("Received onRemoved media event, but it does not refer to the local peer")
            return
        }
        listener.onRemoved(reason)
    }

    override fun onVadNotification(trackId: String, status: String) {
        val trackContext = trackContexts[trackId]
        if (trackContext == null) {
            Timber.e("Invalid track id = $trackId")
            return
        }
        val vadStatus = VadStatus.fromString(status)
        if (vadStatus == null) {
            Timber.e("Invalid vad status = $status")
            return
        }
        trackContext.vadStatus = vadStatus
    }

    override fun onBandwidthEstimation(estimation: Long) {
        listener.onBandwidthEstimationChanged(estimation)
    }

    override fun onError(error: EventTransportError) {
        if (error is EventTransportError.ConnectionError) {
            listener.onError(MembraneRTCError.Transport(error.reason))
        } else {
            listener.onError(MembraneRTCError.Transport(error.message ?: "unknown transport message"))
        }
    }

    override fun onClose() {
        Timber.i("Transport has been closed")
    }

    fun setTargetTrackEncoding(trackId: String, encoding: TrackEncoding) {
        coroutineScope.launch {
            rtcEngineCommunication.setTargetTrackEncoding(trackId, encoding)
        }
    }

    fun enableTrackEncoding(trackId: String, encoding: TrackEncoding) {
        peerConnectionManager.setTrackEncoding(trackId, encoding, true)
    }

    fun disableTrackEncoding(trackId: String, encoding: TrackEncoding) {
        peerConnectionManager.setTrackEncoding(trackId, encoding, false)
    }

    override fun onLocalIceCandidate(candidate: IceCandidate) {
        coroutineScope.launch {
            rtcEngineCommunication.localCandidate(candidate.sdp, candidate.sdpMLineIndex)
        }
    }

    override fun onAddTrack(trackId: String, track: MediaStreamTrack) {
        val trackContext = trackContexts[trackId] ?: run {
            Timber.e("onAddTrack: Track context with trackId=$trackId not found")
            return
        }

        when (track) {
            is VideoTrack ->
                trackContext.track = RemoteVideoTrack(track, eglBase)

            is AudioTrack ->
                trackContext.track = RemoteAudioTrack(track)

            else ->
                throw IllegalStateException("invalid type of incoming track")
        }

        listener.onTrackReady(trackContext)
    }
}
