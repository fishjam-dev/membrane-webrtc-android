package org.membraneframework.rtc

import com.google.gson.reflect.TypeToken
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.membraneframework.rtc.events.*
import org.membraneframework.rtc.utils.Metadata
import org.membraneframework.rtc.utils.SerializedMediaEvent
import timber.log.Timber
import kotlin.math.roundToLong

internal class RTCEngineCommunication
@AssistedInject
constructor(
    @Assisted
    private val engineListener: RTCEngineListener
) {
    @AssistedFactory
    interface RTCEngineCommunicationFactory {
        fun create(
            listener: RTCEngineListener
        ): RTCEngineCommunication
    }

    fun join(peerMetadata: Metadata) {
        sendEvent(Join(peerMetadata))
    }

    fun updatePeerMetadata(peerMetadata: Metadata) {
        sendEvent(UpdatePeerMetadata(peerMetadata))
    }

    fun updateTrackMetadata(trackId: String, trackMetadata: Metadata) {
        sendEvent(UpdateTrackMetadata(trackId, trackMetadata))
    }

    fun setTargetTrackEncoding(trackId: String, encoding: TrackEncoding) {
        sendEvent(
            SelectEncoding(
                trackId,
                encoding.rid
            )
        )
    }

    fun renegotiateTracks() {
        sendEvent(RenegotiateTracks())
    }

    fun localCandidate(sdp: String, sdpMLineIndex: Int) {
        sendEvent(
            LocalCandidate(
                sdp,
                sdpMLineIndex
            )
        )
    }

    fun sdpOffer(
        sdp: String,
        trackIdToTrackMetadata: Map<String, Metadata>,
        midToTrackId: Map<String, String>
    ) {
        sendEvent(
            SdpOffer(
                sdp,
                trackIdToTrackMetadata,
                midToTrackId
            )
        )
    }

    private fun sendEvent(event: SendableEvent) {
        val serializedMediaEvent = gson.toJson(event.serializeToMap())
        engineListener.onSendMediaEvent(serializedMediaEvent)
    }

    private fun decodeEvent(event: SerializedMediaEvent): ReceivableEvent? {
        val type = object : TypeToken<Map<String, Any?>>() {}.type

        val rawMessage: Map<String, Any?> = gson.fromJson(event, type)

        ReceivableEvent.decode(rawMessage)?.let {
            return it
        } ?: run {
            Timber.d("Failed to decode event $rawMessage")
            return null
        }
    }

    fun onEvent(serializedEvent: SerializedMediaEvent) {
        when (val event = decodeEvent(serializedEvent)) {
            is OfferData -> engineListener.onOfferData(event.data.integratedTurnServers, event.data.tracksTypes)
            is PeerAccepted -> engineListener.onPeerAccepted(event.data.id, event.data.peersInRoom)
            is PeerRemoved -> engineListener.onRemoved(event.data.peerId, event.data.reason)
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
            is EncodingSwitched -> engineListener.onTrackEncodingChanged(
                event.data.peerId,
                event.data.trackId,
                event.data.encoding,
                event.data.reason
            )
            is VadNotification -> engineListener.onVadNotification(event.data.trackId, event.data.status)
            is BandwidthEstimation -> engineListener.onBandwidthEstimation(event.data.estimation.roundToLong())
            else -> Timber.e("Failed to process unknown event: $event")
        }
    }
}
