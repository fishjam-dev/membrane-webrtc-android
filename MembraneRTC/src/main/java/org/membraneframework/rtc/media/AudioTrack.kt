package org.membraneframework.rtc.media

import org.webrtc.MediaStreamTrack

open class AudioTrack(protected val audioTrack: org.webrtc.AudioTrack): MediaTrackProvider {
    override fun rtcTrack(): MediaStreamTrack {
        return audioTrack
    }
}