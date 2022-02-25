package org.membraneframework.rtc.media

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.membraneframework.rtc.media.screencast.ScreencastServiceConnector
import org.webrtc.*
import java.util.*

class LocalScreencastTrack
constructor(
    mediaTrack: org.webrtc.VideoTrack,
    context: Context,
    eglBase: EglBase,
    private val capturer: ScreenCapturerAndroid,
    callback: ProjectionCallback
): VideoTrack(mediaTrack, eglBase.eglBaseContext), LocalTrack{
    private val screencastConnection = ScreencastServiceConnector(context)

    init {
        callback.addCallback { stop() }
    }

    suspend fun startForegroundService(notificationId: Int?, notification: Notification?) {
        screencastConnection.connect()
        screencastConnection.start(notificationId, notification)
    }

    override fun start() {
        val preset = VideoParameters.presetScreenShareHD15
        val dimensions = preset.dimensions.flip()

        capturer.startCapture(dimensions.width, dimensions.height, preset.encoding.maxFps)
    }

    override fun stop() {
        screencastConnection.stop()
        capturer.stopCapture()
    }

    override fun enabled(): Boolean {
        return videoTrack.enabled()
    }

    override fun setEnabled(enabled: Boolean) {
        videoTrack.setEnabled(enabled)
    }

    /*
        MediaProjection callback wrapper holding several callbacks that
        will be invoked once the media projections stops.
    */
    class ProjectionCallback: MediaProjection.Callback() {
        var callbacks: ArrayList<() -> Unit> = arrayListOf()

        override fun onStop() {
            callbacks.forEach {
                it.invoke()
            }

            callbacks.clear()
        }

        fun addCallback(callback: () -> Unit) {
            callbacks.add(callback)
        }
    }

    companion object {
        fun create(context: Context, factory: PeerConnectionFactory, eglBase: EglBase, mediaProjectionPermission: Intent, onStopped: () -> Unit): LocalScreencastTrack {
            val source = factory.createVideoSource(true)
            val track = factory.createVideoTrack(UUID.randomUUID().toString(), source)

            val callback = ProjectionCallback()
            callback.addCallback(onStopped)


            val capturer = ScreenCapturerAndroid(mediaProjectionPermission, callback)

            capturer.initialize(
                SurfaceTextureHelper.create("ScreenVideoCaptureThread", eglBase.eglBaseContext),
                context,
                source.capturerObserver
            )

            return LocalScreencastTrack(track, context, eglBase, capturer, callback)
        }
    }
}
