package org.membraneframework.rtc.media

import android.content.Context
import android.provider.MediaStore
import org.webrtc.*
import java.util.*

class LocalVideoTrack constructor(
    mediaTrack: org.webrtc.VideoTrack,
    private val capturer: Capturer,
    private val eglBase: EglBase
): VideoTrack(mediaTrack), LocalTrack{
    enum class Type {
        CAMERA,
        SCREENCAST
    }

    companion object {
        fun create(context: Context, factory: PeerConnectionFactory, eglBase: EglBase, type: Type): LocalVideoTrack {
            val source = factory.createVideoSource(type == Type.SCREENCAST)
            val track = factory.createVideoTrack(UUID.randomUUID().toString(), source)

            val capturer = capturerFor(type, context, eglBase, source)

            return LocalVideoTrack(track, capturer, eglBase)
        }

        private fun capturerFor(type: Type, context: Context, eglBase: EglBase, source: VideoSource): Capturer {
            val preset = VideoParameters.presetQHD169

            return when (type) {
                Type.CAMERA ->
                    // we target vertical dimensions so flip it
                    CameraCapturer(
                        context = context,
                        source = source,
                        rootEglBase = eglBase,
                        videoParameters = preset.copy(dimensions = preset.dimensions.flip())
                    )

                Type.SCREENCAST ->
                    ScreenCapturer()
            }
        }
    }

    override fun start() {
        capturer.startCapture()
    }

    override fun stop() {
        capturer.stopCapture()
    }

    override fun enabled(): Boolean {
        return videoTrack.enabled()
    }

    override fun setEnabled(enabled: Boolean) {
        videoTrack.setEnabled(enabled)
    }
}

interface Capturer {
    fun capturer(): VideoCapturer
    fun startCapture()
    fun stopCapture()

}

class CameraCapturer constructor(
    context: Context,
    source: VideoSource,
    rootEglBase: EglBase,
    private val videoParameters: VideoParameters,
): Capturer {
    private lateinit var videoCapturer: VideoCapturer
    private lateinit var size: Size

    init {
        val enumerator = if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            Camera1Enumerator(true)
        }

        var targetDeviceName: String? = null

        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                // TODO: we may want to listen on camera events such as changing front to back facing
                this.videoCapturer = enumerator.createCapturer(deviceName, null)

                this.videoCapturer.initialize(
                    SurfaceTextureHelper.create("CameraCaptureThread", rootEglBase.eglBaseContext),
                    context,
                    source.capturerObserver
                )

                val sizes = enumerator.getSupportedFormats(deviceName)
                    ?.map { Size(it.width, it.height)}
                    ?: emptyList()

                this.size = CameraEnumerationAndroid.getClosestSupportedSize(sizes, videoParameters.dimensions.height, videoParameters.dimensions.width)

                break
            }
        }
    }

    override fun capturer(): VideoCapturer {
        return videoCapturer
    }

    override fun startCapture() {
        videoCapturer.startCapture(size.height, size.width, videoParameters.encoding.maxFps)
    }

    override fun stopCapture() {
        videoCapturer.stopCapture()
    }
}

class ScreenCapturer constructor(): Capturer {
    override fun capturer(): VideoCapturer {
        TODO("Not yet implemented")
    }

    override fun startCapture() {
        TODO("Not yet implemented")
    }

    override fun stopCapture() {
        TODO("Not yet implemented")
    }
}