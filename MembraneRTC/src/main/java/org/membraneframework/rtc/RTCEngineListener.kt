package org.membraneframework.rtc

import org.membraneframework.rtc.events.OfferData
import org.membraneframework.rtc.models.Endpoint
import org.membraneframework.rtc.utils.Metadata
import org.membraneframework.rtc.utils.SerializedMediaEvent

internal interface RTCEngineListener {
    fun onConnected(endpointID: String, otherEndpoints: List<Endpoint>)
    fun onSendMediaEvent(event: SerializedMediaEvent)
    fun onEndpointAdded(endpoint: Endpoint)
    fun onEndpointRemoved(endpointId: String)
    fun onEndpointUpdated(endpointId: String, endpointMetadata: Metadata)
    fun onOfferData(integratedTurnServers: List<OfferData.TurnServer>, tracksTypes: Map<String, Int>)
    fun onSdpAnswer(type: String, sdp: String, midToTrackId: Map<String, String>)
    fun onRemoteCandidate(candidate: String, sdpMLineIndex: Int, sdpMid: String?)
    fun onTracksAdded(endpointId: String, trackIdToMetadata: Map<String, Metadata>)
    fun onTracksRemoved(endpointId: String, trackIds: List<String>)
    fun onTrackUpdated(endpointId: String, trackId: String, metadata: Metadata)
    fun onTrackEncodingChanged(endpointId: String, trackId: String, encoding: String, encodingReason: String)
    fun onRemoved(endpointId: String, reason: String)
    fun onVadNotification(trackId: String, status: String)
    fun onBandwidthEstimation(estimation: Long)
}
