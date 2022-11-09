package org.membraneframework.rtc

import android.content.Context
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.membraneframework.rtc.media.SimulcastVideoEncoderFactoryWrapper
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import java.util.*

class PeerConnectionFactoryWrapper
@AssistedInject constructor(
    @Assisted private val connectOptions: ConnectOptions,
    audioDeviceModule: AudioDeviceModule,
    eglBase: EglBase,
    appContext: Context
) {
    @AssistedFactory
    interface PeerConnectionFactoryWrapperFactory {
        fun create(
            connectOptions: ConnectOptions
        ): PeerConnectionFactoryWrapper
    }

    val peerConnectionFactory: PeerConnectionFactory

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
        )

        peerConnectionFactory =
            PeerConnectionFactory.builder().setAudioDeviceModule(audioDeviceModule).setVideoEncoderFactory(
                SimulcastVideoEncoderFactoryWrapper(
                    eglBase.eglBaseContext,
                    connectOptions.encoderOptions
                )
            ).setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext)).createPeerConnectionFactory()
    }

    fun createPeerConnection(
        rtcConfig: PeerConnection.RTCConfiguration,
        observer: PeerConnection.Observer
    ): PeerConnection? {
        return peerConnectionFactory.createPeerConnection(rtcConfig, observer)
    }
}
