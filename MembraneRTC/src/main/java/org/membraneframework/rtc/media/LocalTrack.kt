package org.membraneframework.rtc.media

interface LocalTrack {
    fun start()
    fun stop()
    fun enabled(): Boolean
    fun setEnabled(enabled: Boolean)
}

public enum class LocalTrackType {
    AUDIO,
    VIDEO,
    SCREENCAST
}