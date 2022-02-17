package org.membraneframework.rtc.media

import org.webrtc.MediaStreamTrack


interface MediaTrackProvider {
    fun rtcTrack(): MediaStreamTrack
}
