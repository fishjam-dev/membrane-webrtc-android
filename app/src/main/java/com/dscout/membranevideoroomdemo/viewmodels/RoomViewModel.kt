package com.dscout.membranevideoroomdemo.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.membraneframework.rtc.MembraneRTC
import org.membraneframework.rtc.MembraneRTC.ConnectOptions
import org.membraneframework.rtc.MembraneRTCError
import org.membraneframework.rtc.MembraneRTCListener
import org.membraneframework.rtc.models.Peer
import org.membraneframework.rtc.models.TrackContext
import org.membraneframework.rtc.transport.PhoenixTransport
import timber.log.Timber


public class RoomViewModel(
    val url: String,
    application: Application
) : AndroidViewModel(application), MembraneRTCListener {

    var room: MembraneRTC? = null

    fun connect() {
        viewModelScope.async {
            // disconnect from the current view
            room?.disconnect()

            room = MembraneRTC.connect(
                options = ConnectOptions(
                    // TODO: DI for dispatcher and room name as an argument
                    transport = PhoenixTransport(url, "room:room", Dispatchers.IO),
                    config = mapOf("displayName" to "Android User")
                ),
                listener = this@RoomViewModel
            );
        }
    }

    override fun onConnected() {
        room?.join()
    }

    override fun onJoinSuccess(peerID: String, peersInRoom: List<Peer>) {
        Timber.i("Successfully join the room")
    }

    override fun onJoinError(metadata: Any) {
        Timber.e("User has been denied to join the room")
    }

    override fun onTrackReady(ctx: TrackContext) {
        Timber.i("Track is ready $ctx")
    }

    override fun onTrackAdded(ctx: TrackContext) {
        Timber.i("Track has been added $ctx")
    }

    override fun onTrackRemoved(ctx: TrackContext) {
        Timber.i("Track has been removed $ctx")
    }

    override fun onTrackUpdated(ctx: TrackContext) {
        Timber.i("Track has been updated $ctx")
    }

    override fun onPeerJoined(peer: Peer) {
        Timber.i("Peer has joined the room $peer")
    }

    override fun onPeerLeft(peer: Peer) {
        Timber.i("Peer has left the room $peer")
    }

    override fun onPeerUpdated(peer: Peer) {
        Timber.i("Peer has updated $peer")
    }

    override fun onError(error: MembraneRTCError) {
        Timber.e("Encountered an error $error")
    }
}