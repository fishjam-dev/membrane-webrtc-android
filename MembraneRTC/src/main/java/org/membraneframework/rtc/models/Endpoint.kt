package org.membraneframework.rtc.models

import org.membraneframework.rtc.events.TracksAdded
import org.membraneframework.rtc.utils.Metadata

data class Endpoint(
    val id: String,
    val type: String,
    val metadata: Metadata? = mapOf(),
    val trackIdToMetadata: Map<String, Metadata?> = mapOf(),
    val tracks: Map<String, TracksAdded.Data.TrackData>,
) {
    fun withTrack(
        trackId: String,
        metadata: Metadata?
    ): Endpoint {
        val newTrackIdToMetadata = this.trackIdToMetadata.toMutableMap()
        newTrackIdToMetadata[trackId] = metadata ?: mapOf()
        return this.copy(trackIdToMetadata = newTrackIdToMetadata)
    }

    fun withoutTrack(trackId: String): Endpoint {
        val newTrackIdToMetadata = this.trackIdToMetadata.toMutableMap()
        newTrackIdToMetadata.remove(trackId)
        return this.copy(trackIdToMetadata = newTrackIdToMetadata)
    }
}
