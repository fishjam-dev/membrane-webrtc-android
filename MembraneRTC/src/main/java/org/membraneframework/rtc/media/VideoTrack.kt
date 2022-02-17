package org.membraneframework.rtc.media

import org.webrtc.MediaStreamTrack

open class VideoTrack(protected val videoTrack: org.webrtc.VideoTrack): MediaTrackProvider {
    override fun rtcTrack(): MediaStreamTrack {
        return videoTrack
    }
}
