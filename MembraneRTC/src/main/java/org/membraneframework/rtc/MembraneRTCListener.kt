package org.membraneframework.rtc

import org.membraneframework.rtc.models.Endpoint
import org.membraneframework.rtc.models.TrackContext
import org.membraneframework.rtc.utils.SerializedMediaEvent
import timber.log.Timber

interface MembraneRTCListener {
    // Called each time MembraneWebRTC need to send some data to the server.
    fun onSendMediaEvent(event: SerializedMediaEvent)

    // /Callback invoked when the client has been approved to participate in media exchange.
    fun onConnected(endpointID: String, otherEndpoints: List<Endpoint>)

    // /Callback invoked when client has been denied access to enter the room.
    fun onJoinError(metadata: Any)

    // /Callback invoked a track is ready to be played.
    fun onTrackReady(ctx: TrackContext)

    // /Callback invoked a endpoint already present in a room adds a new track.
    fun onTrackAdded(ctx: TrackContext)

    // /Callback invoked when a track will no longer receive any data.
    fun onTrackRemoved(ctx: TrackContext)

    // /Callback invoked when track's metadata gets updated
    fun onTrackUpdated(ctx: TrackContext)

    // /Callback invoked when a new endpoint joins the room.
    fun onEndpointAdded(endpoint: Endpoint)

    // /Callback invoked when a endpoint leaves the room.
    fun onEndpointRemoved(endpoint: Endpoint)

    // /Callback invoked when endpoint's metadata gets updated.
    fun onEndpointUpdated(endpoint: Endpoint)

    // Callback invoked when received track encoding has changed
    @Deprecated("Deprecated, use TrackContext::setOnEncodingChangedListener")
    fun onTrackEncodingChanged(endpointId: String, trackId: String, encoding: String) {
        Timber.i(
            "Track encoding changed $trackId -> $encoding"
        )
    }

    // Callback invoked every time a local endpoint is removed by the server
    fun onRemoved(reason: String) { Timber.e("Endpoint removed") }

    // Called every time the server estimates client's bandwidth.
    // estimation - client's available incoming bitrate estimated
    // by the server. It's measured in bits per second.
    fun onBandwidthEstimationChanged(estimation: Long) {
        Timber.i("Bandwidth estimation changed $estimation")
    }
}
