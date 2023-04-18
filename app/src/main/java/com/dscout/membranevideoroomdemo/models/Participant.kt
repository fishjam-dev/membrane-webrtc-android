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
    val tracksMetadata: HashMap<String, Metadata> = hashMapOf(),
    val vadStatus: VadStatus = VadStatus.SILENCE
) {
    fun addOrUpdateTrackMetadata(videoTrack: VideoTrack, metadata: Metadata) {
        this.tracksMetadata[videoTrack.id()] = metadata
    }

    fun addOrUpdateTrackMetadata(audioTrack: AudioTrack, metadata: Metadata) {
        this.tracksMetadata[audioTrack.id()] = metadata
    }

    fun removeTrackMetadata(videoTrack: VideoTrack) {
        this.tracksMetadata.remove(videoTrack.id())
    }

    fun removeTrackMetadata(audioTrack: AudioTrack) {
        this.tracksMetadata.remove(audioTrack.id())
    }
}
