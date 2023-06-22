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
import org.membraneframework.rtc.models.Endpoint
import org.membraneframework.rtc.models.TrackContext
import org.membraneframework.rtc.transport.PhoenixTransport
import org.membraneframework.rtc.transport.PhoenixTransportError
import org.membraneframework.rtc.transport.PhoenixTransportListener
import org.membraneframework.rtc.utils.SerializedMediaEvent
import timber.log.Timber
import java.util.*

class RoomViewModel(
    val url: String,
    application: Application
) : AndroidViewModel(application), MembraneRTCListener, PhoenixTransportListener {
    // media tracks
    var localAudioTrack: LocalAudioTrack? = null
    var localVideoTrack: LocalVideoTrack? = null
    var localScreencastTrack: LocalScreencastTrack? = null
    private val localEndpointId: String = UUID.randomUUID().toString()

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
    private val params = mapOf<String, Any>("token" to "mocktoken")

    private lateinit var transport: PhoenixTransport

    val videoSimulcastConfig = MutableStateFlow(
        SimulcastConfig(
            enabled = true,
            activeEncodings = listOf(
                TrackEncoding.L,
                TrackEncoding.M,
                TrackEncoding.H
            )
        )
    )
    val screencastSimulcastConfig = MutableStateFlow(
        SimulcastConfig(
            enabled = false
        )
    )

    fun connect(roomName: String, displayName: String) {
        viewModelScope.launch {
            localDisplayName = displayName
            // disconnect from the current view
            room.value?.disconnect()

            val transport = PhoenixTransport(
                url,
                "room:$roomName",
                Dispatchers.IO,
                params,
                mapOf("isSimulcastOn" to true)
            )

            try {
                transport.connect(this@RoomViewModel)
            } catch (e: Exception) {
                Timber.i(e, "Failed to connect")

                errorMessage.value = "Encountered an error, go back and try again..."
                return@launch
            }

            this@RoomViewModel.transport = transport

            room.value = MembraneRTC.create(
                appContext = getApplication(),
                options = CreateOptions(
                    encoderOptions = EncoderOptions(
                        encoderType = EncoderType.SOFTWARE
                    )
                ),
                listener = this@RoomViewModel
            )

            room.value?.connect(mapOf("displayName" to (localDisplayName ?: "")))
        }
    }

    fun disconnect() {
        room.value?.disconnect()
        room.value = null
        transport.disconnect()
    }

    fun focusVideo(participantId: String) {
        val candidates = mutableParticipants.values

        candidates.find {
            it.id == participantId
        }?.let { it ->
            val primaryParticipantTrackId = primaryParticipant.value?.videoTrack?.id()
            if (localVideoTrack?.id() != primaryParticipantTrackId &&
                localScreencastTrack?.id() != primaryParticipantTrackId
            ) {
                val globalId = globalToLocalTrackId.filterValues { it1 ->
                    it1 == primaryParticipantTrackId
                }.keys
                if (globalId.isNotEmpty()) {
                    room.value?.setTargetTrackEncoding(globalId.first(), TrackEncoding.L)
                }
            }
            primaryParticipant.value = it
            val videoTrackId = it.videoTrack?.id()
            if (localVideoTrack?.id() != it.videoTrack?.id() && localScreencastTrack?.id() != videoTrackId) {
                val globalId = globalToLocalTrackId.filterValues { it1 -> it1 == it.videoTrack?.id() }.keys
                if (globalId.isNotEmpty()) {
                    room.value?.setTargetTrackEncoding(globalId.first(), TrackEncoding.H)
                }
            }

            participants.value = candidates.filter { candidate ->
                candidate.id != it.id
            }.toList()
        }
    }

    // TODO: we should preserve some order...
    private fun emitParticipants() {
        val candidates = mutableParticipants.values

        if (candidates.isNotEmpty()) {
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
            room.value?.updateTrackMetadata(it.id(), mapOf("active" to enabled, "type" to "audio"))
        }

        val p = mutableParticipants[localEndpointId]
        if (p != null) {
            mutableParticipants[localEndpointId] = p.updateTrackMetadata(
                p.audioTrack?.id(),
                mapOf("active" to isMicrophoneOn.value)
            )
        }

        emitParticipants()
    }

    fun toggleCamera() {
        localVideoTrack?.let {
            val enabled = !it.enabled()
            it.setEnabled(enabled)
            isCameraOn.value = enabled
            room.value?.updateTrackMetadata(it.id(), mapOf("active" to enabled, "type" to "camera"))
        }

        val p = mutableParticipants[localEndpointId]
        if (p != null) {
            mutableParticipants[localEndpointId] = p.updateTrackMetadata(
                p.videoTrack?.id(),
                mapOf("active" to isCameraOn.value)
            )
        }

        emitParticipants()
    }

    fun flipCamera() {
        localVideoTrack?.flipCamera()
    }

    override fun onSendMediaEvent(event: SerializedMediaEvent) {
        viewModelScope.launch {
            this@RoomViewModel.transport.send(event)
        }
    }

    private fun setupTracks() {
        room.value?.let {
            localAudioTrack = it.createAudioTrack(
                mapOf(
                    "user_id" to (localDisplayName ?: ""),
                    "active" to true,
                    "type" to "audio"
                )
            )

            var videoParameters = VideoParameters.presetHD169
            videoParameters = videoParameters.copy(
                dimensions = videoParameters.dimensions,
                simulcastConfig = videoSimulcastConfig.value,
                maxBitrate = TrackBandwidthLimit.SimulcastBandwidthLimit(
                    mapOf(
                        "l" to TrackBandwidthLimit.BandwidthLimit(150),
                        "m" to TrackBandwidthLimit.BandwidthLimit(500),
                        "h" to TrackBandwidthLimit.BandwidthLimit(1500)
                    )
                )
            )

            localVideoTrack = it.createVideoTrack(
                videoParameters,
                mapOf(
                    "user_id" to (localDisplayName ?: ""),
                    "active" to true,
                    "type" to "camera"
                )
            )

            isCameraOn.value = localVideoTrack?.enabled() ?: false
            isMicrophoneOn.value = localAudioTrack?.enabled() ?: false

            val participant = Participant(localEndpointId, "Me", localVideoTrack, localAudioTrack)

            mutableParticipants[localEndpointId] = participant.updateTrackMetadata(
                participant.audioTrack?.id(),
                mapOf("active" to isMicrophoneOn.value)
            ).updateTrackMetadata(
                participant.videoTrack?.id(),
                mapOf("active" to isCameraOn.value)
            )
        }
    }

    // MembraneRTCListener callbacks
    override fun onConnected(endpointID: String, otherEndpoints: List<Endpoint>) {
        Timber.i("Successfully join the room")

        otherEndpoints.forEach {
            mutableParticipants[it.id] = Participant(
                it.id,
                it.metadata["displayName"] as? String ?: "UNKNOWN",
                null,
                null
            )
        }

        setupTracks()
        emitParticipants()
    }

    override fun onDisconnected() {
        room.value = null
        transport.disconnect()
    }

    override fun onConnectError(metadata: Any) {
        Timber.e("User has been denied to connect to the room")
    }

    override fun onTrackReady(ctx: TrackContext) {
        val participant = mutableParticipants[ctx.endpoint.id] ?: return

        val (id, newParticipant) = when (ctx.track) {
            is RemoteVideoTrack -> {
                globalToLocalTrackId[ctx.trackId] = (ctx.track as RemoteVideoTrack).id()

                if (ctx.metadata["type"] == "screensharing") {
                    Pair(
                        ctx.trackId,
                        participant.copy(
                            id = ctx.trackId,
                            displayName = "${participant.displayName} (screencast)",
                            videoTrack = ctx.track as RemoteVideoTrack
                        )
                    )
                } else {
                    val p = participant.copy(videoTrack = ctx.track as RemoteVideoTrack)
                    Pair(
                        ctx.endpoint.id,
                        p.copy(
                            tracksMetadata = p.tracksMetadata + (
                                (
                                    globalToLocalTrackId[ctx.trackId]
                                        ?: ""
                                    ) to ctx.metadata
                                )
                        )
                    )
                }
            }
            is RemoteAudioTrack -> {
                globalToLocalTrackId[ctx.trackId] = (ctx.track as RemoteAudioTrack).id()
                val p = participant.copy(audioTrack = ctx.track as RemoteAudioTrack)
                Pair(
                    ctx.endpoint.id,
                    p.copy(
                        tracksMetadata = p.tracksMetadata + (
                            (
                                globalToLocalTrackId[ctx.trackId]
                                    ?: ""
                                ) to ctx.metadata
                            )
                    )
                )
            }
            else ->
                throw IllegalArgumentException("invalid type of incoming remote track")
        }

        mutableParticipants[id] = newParticipant

        emitParticipants()

        ctx.setOnVoiceActivityChangedListener {
            val p = mutableParticipants[it.endpoint.id]
            if (p != null) {
                mutableParticipants[it.endpoint.id] = p.copy(vadStatus = it.vadStatus)
                emitParticipants()
            }
        }

        Timber.i("Track is ready $ctx")
    }

    override fun onTrackAdded(ctx: TrackContext) {
        Timber.i("Track has been added $ctx")
    }

    override fun onTrackRemoved(ctx: TrackContext) {
        if (ctx.metadata["type"] == "screensharing") {
            // screencast is a throw-away type of participant so remove it and emit participants once again
            mutableParticipants.remove(ctx.trackId)
            globalToLocalTrackId.remove(ctx.trackId)

            emitParticipants()
        } else {
            val participant = mutableParticipants[ctx.endpoint.id]
                ?: throw IllegalArgumentException("No participant with id ${ctx.endpoint.id}")

            val localTrackId = globalToLocalTrackId[ctx.trackId]
            val audioTrackId = participant.audioTrack?.id()
            val videoTrackId = participant.videoTrack?.id()

            val newParticipant = when {
                localTrackId == videoTrackId ->
                    participant.copy(videoTrack = null)

                localTrackId == audioTrackId ->
                    participant.copy(audioTrack = null)

                else ->
                    throw IllegalArgumentException(
                        "Track ${ctx.trackId} has not been found for given endpoint ${ctx.endpoint.id}"
                    )
            }

            globalToLocalTrackId.remove(ctx.trackId)

            mutableParticipants[ctx.endpoint.id] = newParticipant

            emitParticipants()
        }

        Timber.i("Track has been removed $ctx")
    }

    override fun onTrackUpdated(ctx: TrackContext) {
        val p = mutableParticipants[ctx.endpoint.id]
        if (p != null) {
            // Updates metadata of given track
            if (ctx.metadata["type"] == "camera") {
                mutableParticipants[ctx.endpoint.id] = p.updateTrackMetadata(
                    p.videoTrack?.id(),
                    ctx.metadata
                )
            } else {
                mutableParticipants[ctx.endpoint.id] = p.updateTrackMetadata(
                    p.audioTrack?.id(),
                    ctx.metadata
                )
            }
        }

        emitParticipants()
        Timber.i("Track has been updated $ctx")
    }

    override fun onEndpointAdded(endpoint: Endpoint) {
        mutableParticipants[endpoint.id] = Participant(
            id = endpoint.id,
            displayName = endpoint.metadata["displayName"] as? String ?: "UNKNOWN"
        )

        emitParticipants()
        Timber.i("Endpoint $endpoint has been added")
    }

    override fun onEndpointRemoved(endpoint: Endpoint) {
        mutableParticipants.remove(endpoint.id)

        emitParticipants()
        Timber.i("Endpoint $endpoint has been removed")
    }

    override fun onEndpointUpdated(endpoint: Endpoint) {
        Timber.i("Endpoint $endpoint has been updated")
    }

    fun startScreencast(mediaProjectionPermission: Intent) {
        if (localScreencastTrack != null) return

        isScreenCastOn.value = true

        localScreencastId = UUID.randomUUID().toString()

        var videoParameters = VideoParameters.presetScreenShareHD15
        val dimensions = videoParameters.dimensions.flip()
        videoParameters = videoParameters.copy(
            dimensions = dimensions,
            simulcastConfig = screencastSimulcastConfig.value
        )

        localScreencastTrack = room.value?.createScreencastTrack(
            mediaProjectionPermission,
            videoParameters,
            mapOf(
                "type" to "screensharing",
                "user_id" to (localDisplayName ?: "")
            )
        )

        localScreencastTrack?.let {
            mutableParticipants[localScreencastId!!] = Participant(
                id = localScreencastId!!,
                displayName = "Me (screen cast)",
                videoTrack = it
            )
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

    private fun toggleTrackEncoding(
        simulcastConfig: MutableStateFlow<SimulcastConfig>,
        trackId: String,
        encoding: TrackEncoding
    ) {
        if (simulcastConfig.value.activeEncodings.contains(encoding)) {
            room.value?.disableTrackEncoding(trackId, encoding)
            simulcastConfig.value = SimulcastConfig(
                true,
                simulcastConfig.value.activeEncodings.filter { it != encoding }
            )
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

    override fun onEvent(event: SerializedMediaEvent) {
        room.value?.receiveMediaEvent(event)
    }

    override fun onError(error: PhoenixTransportError) {
        Timber.e("Encountered an error $error")
        errorMessage.value = "Encountered an error, go back and try again..."
    }

    override fun onClose() {
    }
}
