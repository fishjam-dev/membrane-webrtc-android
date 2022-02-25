package com.dscout.membranevideoroomdemo.viewmodels

import android.app.Application
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dscout.membranevideoroomdemo.models.Participant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.membraneframework.rtc.ConnectOptions
import org.membraneframework.rtc.MembraneRTC
import org.membraneframework.rtc.MembraneRTCError
import org.membraneframework.rtc.MembraneRTCListener
import org.membraneframework.rtc.media.AudioTrack
import org.membraneframework.rtc.media.RemoteAudioTrack
import org.membraneframework.rtc.media.RemoteVideoTrack
import org.membraneframework.rtc.media.VideoTrack
import org.membraneframework.rtc.models.Peer
import org.membraneframework.rtc.models.TrackContext
import org.membraneframework.rtc.transport.PhoenixTransport
import timber.log.Timber
import java.lang.IllegalArgumentException
import java.util.*
import kotlin.collections.HashMap

public class RoomViewModel(
    val url: String,
    application: Application,
) : AndroidViewModel(application), MembraneRTCListener {
    // NOTE: why do we differentiate that?
    private var mutableRoom = MutableStateFlow<MembraneRTC?>(null)
    val room: MutableStateFlow<MembraneRTC?> = mutableRoom

    private val mutableParticipants = HashMap<String, Participant>()

    val primaryParticipant = MutableStateFlow<Participant?>(null)
    val participants = MutableStateFlow<List<Participant>>(emptyList())

    val isMicrophoneOn = MutableStateFlow<Boolean>(false)
    val isCameraOn = MutableStateFlow<Boolean>(false)
    val isScreenCastOn = MutableStateFlow<Boolean>(false)
    val errorMessage = MutableStateFlow<String?>(null)

    private var localScreencastId: String? = null

    private val globalToLocalTrackId = HashMap<String, String>()

    fun connect(roomName: String, displayName: String) {
        viewModelScope.launch {
            // disconnect from the current view
            mutableRoom.value?.disconnect()

            mutableRoom.value = MembraneRTC.connect(
                appContext = getApplication(),
                options = ConnectOptions(
                    // TODO: DI for dispatcher and room name as an argument
                    transport = PhoenixTransport(url, "room:$roomName", Dispatchers.IO),
                    config = mapOf("displayName" to displayName)
                ),
                listener = this@RoomViewModel
            );
        }
    }

    fun disconnect() {
        mutableRoom.value?.disconnect()

        mutableRoom.value = null
    }

    fun focusVideo(participantId: String) {
        val candidates = mutableParticipants.values.filter {
            it.videoTrack != null
        }

        candidates.find {
            it.id == participantId
        }?.let {
            primaryParticipant.value = it

            participants.value = candidates.filter { candidate ->
                candidate.id != it.id
            }.toList()
        }
    }

    // TODO: we should preserve some order...
    private fun emitParticipants() {
        val candidates = mutableParticipants.values.filter {
            it.videoTrack != null
        }

        if (candidates.count() > 0) {
            val primary = candidates.first()

            primaryParticipant.value = primary

            // filter out participants that have no active video tracks for now
           participants.value = candidates.drop(1).toList()
        }
    }

    // controls
    fun toggleMicrophone() {
        mutableRoom.value?.localAudioTrack()?.let {
            val enabled = !it.enabled()
            it.setEnabled(enabled)
            isMicrophoneOn.value = enabled
        }
    }

    fun toggleCamera() {
        mutableRoom.value?.localVideoTrack()?.let {
            val enabled = !it.enabled()
            it.setEnabled(enabled)
            isCameraOn.value = enabled
        }
    }

    fun flipCamera() {
        mutableRoom.value?.localVideoTrack()?.flipCamera()
    }

    // MembraneRTCListener callbacks
    override fun onConnected() {
        mutableRoom.value?.let {
            it.join()
            val localPeerId = UUID.randomUUID().toString()
            val localVideoTrack = it.localVideoTrack()
            val localAudioTrack = it.localAudioTrack()

            isCameraOn.value = localVideoTrack?.enabled() ?: false
            isMicrophoneOn.value = localAudioTrack?.enabled() ?: false

            mutableParticipants[localPeerId] = Participant(localPeerId, "Me", localVideoTrack, localAudioTrack)

            emitParticipants()
        }
    }

    override fun onJoinSuccess(peerID: String, peersInRoom: List<Peer>) {
        Timber.i("Successfully join the room")

        peersInRoom.forEach {
            mutableParticipants[it.id] = Participant(it.id, it.metadata["displayName"] ?: "UNKNOWN", null, null)
        }

        emitParticipants()
    }

    override fun onJoinError(metadata: Any) {
        Timber.e("User has been denied to join the room")
    }

    override fun onTrackReady(ctx: TrackContext) {
        viewModelScope.launch {
            val participant = mutableParticipants[ctx.peer.id] ?: return@launch

            val newParticipant = when (ctx.track) {
                is RemoteVideoTrack -> {
                    globalToLocalTrackId[ctx.trackId] = (ctx.track as RemoteVideoTrack).id()

                    participant.copy(videoTrack = ctx.track as RemoteVideoTrack)
                }
                is RemoteAudioTrack -> {
                    globalToLocalTrackId[ctx.trackId] = (ctx.track as RemoteAudioTrack).id()

                     participant.copy(audioTrack = ctx.track as RemoteAudioTrack)
                }
                else ->
                    throw IllegalArgumentException("invalid type of incoming remote track")
            }

            mutableParticipants[ctx.peer.id] = newParticipant

            emitParticipants()
        }

        Timber.i("Track is ready $ctx")
    }

    override fun onTrackAdded(ctx: TrackContext) {
        Timber.i("Track has been added $ctx")
    }

    override fun onTrackRemoved(ctx: TrackContext) {
        viewModelScope.launch {
            val participant = mutableParticipants[ctx.peer.id] ?: return@launch

            val localTrackId = globalToLocalTrackId[ctx.trackId]
            val audioTrackId = participant.audioTrack?.id()
            val videoTrackId = participant.videoTrack?.id()

            val newParticipant = when {
                localTrackId == videoTrackId ->
                    participant.copy(videoTrack = null)

                localTrackId == audioTrackId ->
                    participant.copy(audioTrack = null)

                else ->
                     throw IllegalArgumentException("track has not been found for given peer")
            }

            globalToLocalTrackId.remove(ctx.trackId)

            mutableParticipants[ctx.peer.id] = newParticipant

            emitParticipants()
        }

        Timber.i("Track has been removed $ctx")
    }

    override fun onTrackUpdated(ctx: TrackContext) {
        Timber.i("Track has been updated $ctx")
    }

    override fun onPeerJoined(peer: Peer) {
        mutableParticipants[peer.id] = Participant(id = peer.id, displayName = peer.metadata["displayName"] ?: "UNKNOWN")

        Timber.i("Peer has joined the room $peer")
    }

    override fun onPeerLeft(peer: Peer) {
        mutableParticipants.remove(peer.id)

        emitParticipants()
        Timber.i("Peer has left the room $peer")
    }

    override fun onPeerUpdated(peer: Peer) {
        Timber.i("Peer has updated $peer")
    }

    override fun onError(error: MembraneRTCError) {
        Timber.e("Encountered an error $error")
        errorMessage.value = "Encountered an error, go back and try again..."
    }

    fun startScreencast(mediaProjectionPermission: Intent) {
        isScreenCastOn.value = true

        localScreencastId = UUID.randomUUID().toString()

        mutableRoom.value?.startScreencast(mediaProjectionPermission, onEnd = {
            stopScreencast()
        })

        mutableRoom.value?.localScreencastTrack()?.let {
            mutableParticipants[localScreencastId!!] = Participant(id = localScreencastId!!, displayName = "Me (screen cast)", videoTrack = it)
            emitParticipants()
        }
    }

    fun stopScreencast() {
        isScreenCastOn.value = false
        mutableRoom.value?.stopScreencast()

        localScreencastId?.let {
            mutableParticipants.remove(it)

            emitParticipants()
        }
    }
}