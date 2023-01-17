package org.membraneframework.rtc

import org.membraneframework.rtc.models.Peer
import org.membraneframework.rtc.models.TrackContext
import timber.log.Timber

public interface MembraneRTCListener {
    // / Callback invoked when client has successfully connected via transport layer.
    fun onConnected()

    // /Callback invoked when the client has been approved to participate in media exchange.
    fun onJoinSuccess(peerID: String, peersInRoom: List<Peer>)

    // /Callback invoked when client has been denied access to enter the room.
    fun onJoinError(metadata: Any)

    // /Callback invoked a track is ready to be played.
    fun onTrackReady(ctx: TrackContext)

    // /Callback invoked a peer already present in a room adds a new track.
    fun onTrackAdded(ctx: TrackContext)

    // /Callback invoked when a track will no longer receive any data.
    fun onTrackRemoved(ctx: TrackContext)

    // /Callback invoked when track's metadata gets updated
    fun onTrackUpdated(ctx: TrackContext)

    // /Callback invoked when a new peer joins the room.
    fun onPeerJoined(peer: Peer)

    // /Callback invoked when a peer leaves the room.
    fun onPeerLeft(peer: Peer)

    // /Callback invoked when peer's metadata gets updated.
    fun onPeerUpdated(peer: Peer)

    // Callback invoked when received track encoding has changed
    @Deprecated("Deprecated, use TrackContext::setOnTrackEncodingChangeListener")
    fun onTrackEncodingChanged(peerId: String, trackId: String, encoding: String) {
        Timber.i(
            "Track encoding changed $trackId -> $encoding"
        )
    }

    // /Callback invoked when an errors happens.
    fun onError(error: MembraneRTCError)

    // Callback invoked every time a local peer is removed by the server
    fun onRemoved(reason: String) { Timber.e("Peer removed") }

    // Called every time the server estimates client's bandwidth.
    // estimation - client's available incoming bitrate estimated
    // by the server. It's measured in bits per second.
    fun onBandwidthEstimationChanged(estimation: Long) {
        Timber.i("Bandwidth estimation changed $estimation")
    }
}
