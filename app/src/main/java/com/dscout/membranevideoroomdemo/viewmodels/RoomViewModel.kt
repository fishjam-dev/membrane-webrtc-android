package com.dscout.membranevideoroomdemo.viewmodels

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dscout.membranevideoroomdemo.models.Participant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.membraneframework.rtc.*
import org.membraneframework.rtc.media.*
import org.membraneframework.rtc.models.Peer
import org.membraneframework.rtc.models.TrackContext
import org.membraneframework.rtc.transport.PhoenixTransport
import timber.log.Timber
import java.util.*


public class RoomViewModel(
    val url: String,
    application: Application,
) : AndroidViewModel(application), MembraneRTCListener {
    // media tracks
    var localAudioTrack: LocalAudioTrack? = null
    var localVideoTrack: LocalVideoTrack? = null
    var localScreencastTrack: LocalScreencastTrack? = null

    var localDisplayName: String? = null

    private var room = MutableStateFlow<MembraneRTC?>(null)

    private val mutableParticipants = HashMap<String, Participant>()

    val primaryParticipant = MutableStateFlow<Participant?>(null)
    val participants = MutableStateFlow<List<Participant>>(emptyList())

    val isMicrophoneOn = MutableStateFlow(false)
    val isCameraOn = MutableStateFlow(false)
    val isScreenCastOn = MutableStateFlow(false)
    val errorMessage = MutableStateFlow<String?>(null)

    private var localScreencastId: String? = null

    private val globalToLocalTrackId = HashMap<String, String>()

    val videoSimulcastConfig = MutableStateFlow(SimulcastConfig(enabled = true, activeEncodings = listOf(
        TrackEncoding.L,
        TrackEncoding.M,
        TrackEncoding.H))
    )
    val screencastSimulcastConfig = MutableStateFlow(SimulcastConfig(enabled = true, activeEncodings = listOf(
        TrackEncoding.L,
        TrackEncoding.M,
        TrackEncoding.H)
    ))

    fun connect(roomName: String, displayName: String) {
        viewModelScope.launch {
            localDisplayName = displayName
            // disconnect from the current view
            room.value?.disconnect()

            room.value = MembraneRTC.connect(
                appContext = getApplication(),
                options = ConnectOptions(
                    transport = PhoenixTransport(url, "room:$roomName", Dispatchers.IO),
                    config = mapOf("displayName" to displayName)
                ),
                listener = this@RoomViewModel
            );
        }
    }

    fun disconnect() {
        room.value?.disconnect()

        room.value = null
    }

    fun focusVideo(participantId: String) {
        val candidates = mutableParticipants.values.filter {
            it.videoTrack != null
        }

        candidates.find {
            it.id == participantId
        }?.let { it ->
            val primaryParticipantTrackId = primaryParticipant.value?.videoTrack?.id()
            if(localVideoTrack?.id() != primaryParticipantTrackId && localScreencastTrack?.id() != primaryParticipantTrackId) {
                val globalId = globalToLocalTrackId.filterValues { it1 -> it1 == primaryParticipantTrackId }.keys.first()
                primaryParticipant.value?.id?.let { it1 -> room.value?.selectTrackEncoding(it1, globalId, TrackEncoding.L) }
            }
            primaryParticipant.value = it
            val videoTrackId = it.videoTrack?.id()
            if(localVideoTrack?.id() != it.videoTrack?.id() && localScreencastTrack?.id() != videoTrackId) {
                val globalId = globalToLocalTrackId.filterValues { it1 -> it1 == it.videoTrack?.id() }.keys.first()
                room.value?.selectTrackEncoding(participantId, globalId, TrackEncoding.H)
            }

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
        localAudioTrack?.let {
            val enabled = !it.enabled()
            it.setEnabled(enabled)
            isMicrophoneOn.value = enabled
        }
    }

    fun toggleCamera() {
        localVideoTrack?.let {
            val enabled = !it.enabled()
            it.setEnabled(enabled)
            isCameraOn.value = enabled
        }
    }

    fun flipCamera() {
        localVideoTrack?.flipCamera()
    }

    // MembraneRTCListener callbacks
    override fun onConnected() {
        room.value?.let {
            localAudioTrack = it.createAudioTrack(mapOf(
                "user_id" to (localDisplayName ?: "")
            ))

            var videoParameters = VideoParameters.presetFHD169
            videoParameters = videoParameters.copy(dimensions = videoParameters.dimensions.flip())

            localVideoTrack = it.createVideoTrack(videoParameters, mapOf(
                "user_id" to (localDisplayName ?: "")
            ), videoSimulcastConfig.value)

            it.join()

            isCameraOn.value = localVideoTrack?.enabled() ?: false
            isMicrophoneOn.value = localAudioTrack?.enabled() ?: false

            val localPeerId = UUID.randomUUID().toString()
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

            val (id, newParticipant) = when (ctx.track) {
                is RemoteVideoTrack -> {
                    globalToLocalTrackId[ctx.trackId] = (ctx.track as RemoteVideoTrack).id()

                    if (ctx.metadata["type"] == "screensharing") {
                        Pair(ctx.trackId, participant.copy(id = ctx.trackId, displayName = "${participant.displayName} (screencast)", videoTrack = ctx.track as RemoteVideoTrack))
                    } else {
                        Pair(ctx.peer.id, participant.copy(videoTrack = ctx.track as RemoteVideoTrack))
                    }
                }
                is RemoteAudioTrack -> {
                    globalToLocalTrackId[ctx.trackId] = (ctx.track as RemoteAudioTrack).id()

                    Pair(ctx.peer.id, participant.copy(audioTrack = ctx.track as RemoteAudioTrack))
                }
                else ->
                    throw IllegalArgumentException("invalid type of incoming remote track")
            }

            mutableParticipants[id] = newParticipant

            emitParticipants()
        }

        Timber.i("Track is ready $ctx")
    }

    override fun onTrackAdded(ctx: TrackContext) {
        Timber.i("Track has been added $ctx")
    }

    override fun onTrackRemoved(ctx: TrackContext) {
        viewModelScope.launch {
            if (ctx.metadata["type"] == "screensharing") {
                // screencast is a throw-away type of participant so remove it and emit participants once again
                mutableParticipants.remove(ctx.trackId)
                globalToLocalTrackId.remove(ctx.trackId)

                emitParticipants()
            } else {
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
        if (localScreencastTrack != null) return

        isScreenCastOn.value = true

        localScreencastId = UUID.randomUUID().toString()

        var videoParameters = VideoParameters.presetScreenShareHD15
        val dimensions = videoParameters.dimensions.flip()
        videoParameters = videoParameters.copy(dimensions = dimensions)

        localScreencastTrack = room.value?.createScreencastTrack(mediaProjectionPermission, videoParameters, mapOf(
            "type" to "screensharing",
            "user_id" to (localDisplayName ?: ""),
        ), screencastSimulcastConfig.value) {
            stopScreencast()
        }

        localScreencastTrack?.let {
            mutableParticipants[localScreencastId!!] = Participant(id = localScreencastId!!, displayName = "Me (screen cast)", videoTrack = it)
            emitParticipants()
        }
    }

    fun stopScreencast() {
        isScreenCastOn.value = false

        localScreencastTrack?.let {
            room.value?.removeTrack(it.id())

            localScreencastId?.let {
                mutableParticipants.remove(it)

                emitParticipants()
            }

            localScreencastTrack = null
        }
    }

    private fun toggleTrackEncoding(simulcastConfig: MutableStateFlow<SimulcastConfig>, trackId: String, encoding: TrackEncoding) {
        if(simulcastConfig.value.activeEncodings.contains(encoding)) {
            room.value?.disableTrackEncoding(trackId, encoding)
            simulcastConfig.value = SimulcastConfig(true, simulcastConfig.value.activeEncodings.filter { it != encoding })
        } else {
            room.value?.enableTrackEncoding(trackId, encoding)
            simulcastConfig.value = SimulcastConfig(true, simulcastConfig.value.activeEncodings.plus(encoding))
        }
    }

    fun toggleVideoTrackEncoding(encoding: TrackEncoding) {
        localVideoTrack?.id()?.let { toggleTrackEncoding(videoSimulcastConfig, it, encoding) }
    }

    fun toggleScreencastTrackEncoding(encoding: TrackEncoding) {
        localScreencastTrack?.id()?.let { toggleTrackEncoding(screencastSimulcastConfig, it, encoding) }
    }
}