package org.membraneframework.rtc

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.membraneframework.rtc.events.*
import org.membraneframework.rtc.transport.EventTransport
import org.membraneframework.rtc.transport.EventTransportError
import org.membraneframework.rtc.transport.EventTransportListener
import org.membraneframework.rtc.utils.Metadata
import timber.log.Timber

class RTCEngineCommunication
@AssistedInject
constructor(
    @Assisted
    private val connectOptions: ConnectOptions,
    @Assisted
    private val engineListener: RTCEngineListener
) : EventTransportListener {
    private var transport: EventTransport = connectOptions.transport

    @AssistedFactory
    interface RTCEngineCommunicationFactory {
        fun create(
            connectOptions: ConnectOptions,
            listener: RTCEngineListener
        ): RTCEngineCommunication
    }

    suspend fun connect() {
        transport.connect(this@RTCEngineCommunication)
    }

    suspend fun disconnect() {
        transport.disconnect()
    }

    suspend fun join(peerMetadata: Metadata) {
        transport.send(Join(peerMetadata))
    }

    suspend fun updatePeerMetadata(peerMetadata: Metadata) {
        transport.send(UpdatePeerMetadata(peerMetadata))
    }

    suspend fun updateTrackMetadata(trackId: String, trackMetadata: Metadata) {
        transport.send(UpdateTrackMetadata(trackId, trackMetadata))
    }

    suspend fun setTargetTrackEncoding(trackId: String, encoding: TrackEncoding) {
        transport.send(
            SelectEncoding(
                trackId,
                encoding.rid
            )
        )
    }

    suspend fun renegotiateTracks() {
        transport.send(RenegotiateTracks())
    }

    suspend fun localCandidate(sdp: String, sdpMLineIndex: Int) {
        transport.send(
            LocalCandidate(
                sdp,
                sdpMLineIndex
            )
        )
    }

    suspend fun sdpOffer(
        sdp: String,
        trackIdToTrackMetadata: Map<String, Metadata>,
        midToTrackId: Map<String, String>
    ) {
        transport.send(
            SdpOffer(
                sdp,
                trackIdToTrackMetadata,
                midToTrackId
            )
        )
    }

    override fun onEvent(event: ReceivableEvent) {
        when (event) {
            is OfferData -> engineListener.onOfferData(event.data.integratedTurnServers, event.data.tracksTypes)
            is PeerAccepted -> engineListener.onPeerAccepted(event.data.id, event.data.peersInRoom)
            is PeerDenied -> engineListener.onPeerDenied()
            is PeerJoined -> engineListener.onPeerJoined(event.data.peer)
            is PeerLeft -> engineListener.onPeerLeft(event.data.peerId)
            is PeerUpdated -> engineListener.onPeerUpdated(event.data.peerId, event.data.metadata)
            is RemoteCandidate -> engineListener.onRemoteCandidate(
                event.data.candidate,
                event.data.sdpMLineIndex,
                event.data.sdpMid
            )
            is SdpAnswer -> engineListener.onSdpAnswer(event.data.type, event.data.sdp, event.data.midToTrackId)
            is TrackUpdated -> engineListener.onTrackUpdated(event.data.peerId, event.data.trackId, event.data.metadata)
            is TracksAdded -> engineListener.onTracksAdded(event.data.peerId, event.data.trackIdToMetadata)
            is TracksRemoved -> engineListener.onTracksRemoved(event.data.peerId, event.data.trackIds)
            else -> Timber.e("Failed to process unknown event: $event")
        }
    }

    override fun onError(error: EventTransportError) {
        engineListener.onError(error)
    }

    override fun onClose() {
        engineListener.onClose()
    }
}
