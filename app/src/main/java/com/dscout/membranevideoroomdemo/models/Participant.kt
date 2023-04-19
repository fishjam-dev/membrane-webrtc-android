package com.dscout.membranevideoroomdemo.models

import org.membraneframework.rtc.media.AudioTrack
import org.membraneframework.rtc.media.VideoTrack
import org.membraneframework.rtc.models.VadStatus
import org.membraneframework.rtc.utils.Metadata


data class Participant(
    val id: String,
    val displayName: String,
    val videoTrack: VideoTrack? = null,
    val audioTrack: AudioTrack? = null,
    val isScreencast: Boolean = false,
    val tracksMetadata: Map<String, Metadata> = emptyMap(),
    val vadStatus: VadStatus = VadStatus.SILENCE
)
