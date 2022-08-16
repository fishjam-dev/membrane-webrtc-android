package org.membraneframework.rtc

import org.webrtc.RtpParameters

class Constants {
    companion object {
        val encodings = listOf(
            RtpParameters.Encoding("l", false, 4.0),
            RtpParameters.Encoding("m", false, 2.0),
            RtpParameters.Encoding("h", false, 1.0),
        )
    }
}