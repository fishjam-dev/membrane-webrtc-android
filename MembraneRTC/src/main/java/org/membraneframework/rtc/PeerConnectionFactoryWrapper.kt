package org.membraneframework.rtc

import android.content.Context
import org.membraneframework.rtc.media.SimulcastVideoEncoderFactoryWrapper
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule

internal class PeerConnectionFactoryWrapper
    constructor(
        private val createOptions: CreateOptions,
        audioDeviceModule: AudioDeviceModule,
        eglBase: EglBase,
        appContext: Context
    ) {
        interface PeerConnectionFactoryWrapperFactory {
            fun create(createOptions: CreateOptions): PeerConnectionFactoryWrapper
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
                        createOptions.encoderOptions
                    )
                ).setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                    .createPeerConnectionFactory()
        }

        fun createPeerConnection(
            rtcConfig: PeerConnection.RTCConfiguration,
            observer: PeerConnection.Observer
        ): PeerConnection? {
            return peerConnectionFactory.createPeerConnection(rtcConfig, observer)
        }
    }
