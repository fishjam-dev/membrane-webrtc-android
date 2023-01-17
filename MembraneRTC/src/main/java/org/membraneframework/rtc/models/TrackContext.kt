package org.membraneframework.rtc.models

import org.membraneframework.rtc.TrackEncoding
import org.membraneframework.rtc.media.RemoteTrack
import org.membraneframework.rtc.utils.Metadata

data class TrackContext internal constructor(
    val track: RemoteTrack?,
    val peer: Peer,
    val trackId: String,
    val metadata: Metadata,
    val vadStatus: VadStatus,
    val encoding: TrackEncoding?,
    val encodingReason: EncodingReason?,
    private val trackContextInternal: TrackContextInternal
) {
    // Callback invoked when received track encoding has changed
    fun setOnTrackEncodingChangeListener(listener: ((TrackContext) -> Unit)?) {
        trackContextInternal.setOnTrackEncodingChangeListener(listener)
    }

    // Callback invoked every time an update about voice activity is received from the server
    fun setOnVadNotificationListener(listener: ((TrackContext) -> Unit)?) {
        trackContextInternal.setOnVadNotificationListener(listener)
    }
}
