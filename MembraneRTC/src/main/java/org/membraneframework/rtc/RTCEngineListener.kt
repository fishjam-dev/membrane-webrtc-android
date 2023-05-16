package org.membraneframework.rtc

import org.membraneframework.rtc.events.OfferData
import org.membraneframework.rtc.models.Peer
import org.membraneframework.rtc.utils.Metadata
import org.membraneframework.rtc.utils.SerializedMediaEvent

internal interface RTCEngineListener {
    fun onSendMediaEvent(event: SerializedMediaEvent)
    fun onPeerAccepted(peerId: String, peersInRoom: List<Peer>)
    fun onPeerDenied()
    fun onPeerJoined(peer: Peer)
    fun onPeerLeft(peerId: String)
    fun onPeerUpdated(peerId: String, peerMetadata: Metadata)
    fun onOfferData(integratedTurnServers: List<OfferData.TurnServer>, tracksTypes: Map<String, Int>)
    fun onSdpAnswer(type: String, sdp: String, midToTrackId: Map<String, String>)
    fun onRemoteCandidate(candidate: String, sdpMLineIndex: Int, sdpMid: String?)
    fun onTracksAdded(peerId: String, trackIdToMetadata: Map<String, Metadata>)
    fun onTracksRemoved(peerId: String, trackIds: List<String>)
    fun onTrackUpdated(peerId: String, trackId: String, metadata: Metadata)
    fun onTrackEncodingChanged(peerId: String, trackId: String, encoding: String, encodingReason: String)
    fun onRemoved(peerId: String, reason: String)
    fun onVadNotification(trackId: String, status: String)
    fun onBandwidthEstimation(estimation: Long)
}
