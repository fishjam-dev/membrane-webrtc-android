package org.membraneframework.rtc.dagger

import android.content.Context
import org.membraneframework.rtc.InternalMembraneRTC
import org.webrtc.EglBase
import org.webrtc.audio.AudioDeviceModule

internal interface MembraneRTCComponent {
    fun membraneRTCFactory(): InternalMembraneRTC

    fun eglBase(): EglBase

    fun audioDeviceModule(): AudioDeviceModule

    interface Factory {
        fun create(appContext: Context): MembraneRTCComponent
    }
}
