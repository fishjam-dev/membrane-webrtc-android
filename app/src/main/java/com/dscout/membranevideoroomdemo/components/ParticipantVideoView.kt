package com.dscout.membranevideoroomdemo.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.dscout.membranevideoroomdemo.models.Participant
import org.membraneframework.rtc.media.VideoTrack
import org.membraneframework.rtc.ui.VideoTextureViewRenderer
import org.webrtc.RendererCommon
import timber.log.Timber


public enum class VideoViewLayout {
    FIT,
    FILL;

    internal fun toScalingType(): RendererCommon.ScalingType {
        return when (this) {
            FIT -> RendererCommon.ScalingType.SCALE_ASPECT_FIT
            FILL -> RendererCommon.ScalingType.SCALE_ASPECT_FILL
        }
    }
}


// TODO: come back here mate...
@Composable
fun ParticipantVideoView(
    participant: Participant,
    videoViewLayout: VideoViewLayout,
    modifier: Modifier = Modifier
) {
    var activeVideoTrack by remember { mutableStateOf<VideoTrack?>(null)}
    var view: VideoTextureViewRenderer? by remember { mutableStateOf(null) }

    fun setupTrack(videoTrack: VideoTrack, view: VideoTextureViewRenderer) {
        if (activeVideoTrack == videoTrack) return

        activeVideoTrack?.removeRenderer(view)
        videoTrack.addRenderer(view)
        activeVideoTrack = videoTrack
    }

    LaunchedEffect(participant.videoTrack) {
        Timber.i("Launched participant video view with a track ${participant.videoTrack!!.id()}")
    }

    DisposableEffect(participant.videoTrack) {
        onDispose {
            view?.let {
                Timber.i("Removing current renderer for ${participant.id}")
                participant.videoTrack!!.removeRenderer(it)
            }
        }
    }

    DisposableEffect(currentCompositeKeyHash.toString()) {
        onDispose {
            Timber.i("Disposing the current participant video view for ${participant.id}")
            view?.release()
        }
    }


    AndroidView(
        factory = { context ->
            VideoTextureViewRenderer(context).apply {
                Timber.i("Adding a renderer for participant ${participant.id}")
                this.init(participant.videoTrack!!.eglContext, null)

                this.setScalingType(videoViewLayout.toScalingType())
                this.setEnableHardwareScaler(true)

                setupTrack(participant.videoTrack, this)

                view = this
            }
        },
        update = { updatedView ->
            Timber.i("Updating view for participant ${participant.id}")
            setupTrack(participant.videoTrack!!, updatedView)
        },
        modifier = modifier
    )
}