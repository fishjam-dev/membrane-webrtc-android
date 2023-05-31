package org.membraneframework.rtc.models

import android.util.Log
import org.membraneframework.rtc.utils.Metadata

data class Endpoint(val id: String, val metadata: Metadata, val trackIdToMetadata: Map<String, Metadata>) {
    fun withTrack(trackId: String, metadata: Metadata): Endpoint {
        val newTrackIdToMetadata = this.trackIdToMetadata.toMutableMap()
        newTrackIdToMetadata[trackId] = metadata
        Log.d("SIEMANO Z", "Z")
        return this.copy(trackIdToMetadata = newTrackIdToMetadata)
    }

    fun withoutTrack(trackId: String): Endpoint {
        val newTrackIdToMetadata = this.trackIdToMetadata.toMutableMap()
        newTrackIdToMetadata.remove(trackId)
        Log.d("SIEMANO BEZ", "BEZ")

        return this.copy(trackIdToMetadata = newTrackIdToMetadata)
    }
}
