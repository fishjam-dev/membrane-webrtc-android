package org.membraneframework.rtc

import android.content.Context
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.membraneframework.rtc.media.SimulcastVideoEncoderFactoryWrapper
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule

internal class EndpointConnectionFactoryWrapper
@AssistedInject constructor(
    @Assisted private val createOptions: CreateOptions,
    audioDeviceModule: AudioDeviceModule,
    eglBase: EglBase,
    appContext: Context
) {
    @AssistedFactory
    interface EndpointConnectionFactoryWrapperFactory {
        fun create(
            createOptions: CreateOptions
        ): EndpointConnectionFactoryWrapper
    }

    val endpointConnectionFactory: PeerConnectionFactory

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
        )

        endpointConnectionFactory =
            PeerConnectionFactory.builder().setAudioDeviceModule(audioDeviceModule).setVideoEncoderFactory(
                SimulcastVideoEncoderFactoryWrapper(
                    eglBase.eglBaseContext,
                    createOptions.encoderOptions
                )
            ).setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext)).createPeerConnectionFactory()
    }

    fun createEndpointConnection(
        rtcConfig: PeerConnection.RTCConfiguration,
        observer: PeerConnection.Observer
    ): PeerConnection? {
        return endpointConnectionFactory.createPeerConnection(rtcConfig, observer)
    }
}
