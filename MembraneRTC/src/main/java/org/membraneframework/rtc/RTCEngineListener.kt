package org.membraneframework.rtc

import org.membraneframework.rtc.events.OfferData
import org.membraneframework.rtc.models.Peer
import org.membraneframework.rtc.transport.EventTransportError
import org.membraneframework.rtc.utils.Metadata

internal interface RTCEngineListener {
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
    fun onError(error: EventTransportError)
    fun onClose()
}
