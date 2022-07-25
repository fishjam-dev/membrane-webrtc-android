package org.membraneframework.rtc

enum class TrackEncoding(val rid: String) {
    L("l"), M("m"), H("h");
}

data class SimulcastConfig(val enabled: Boolean, val activeEncodings: List<TrackEncoding> = listOf())
