package org.membraneframework.rtc.models

import org.membraneframework.rtc.TrackEncoding
import org.membraneframework.rtc.media.RemoteTrack
import org.membraneframework.rtc.utils.Metadata
import kotlin.properties.Delegates

internal class TrackContextInternal(
    var track: RemoteTrack?,
    val peer: Peer,
    val trackId: String,
    var metadata: Metadata
) {
    private var onTrackEncodingChangeListener: ((TrackContext) -> Unit)? = null
    private var onVadNotificationListener: ((TrackContext) -> Unit)? = null

    var vadStatus: VadStatus by Delegates.observable(VadStatus.SILENCE) { _, _, _ ->
        onVadNotificationListener?.let { it(toTrackContext()) }
    }

    private var encoding: TrackEncoding? = null
    private var encodingReason: EncodingReason? = null

    fun setEncoding(encoding: TrackEncoding, encodingReason: EncodingReason) {
        this.encoding = encoding
        this.encodingReason = encodingReason
        onTrackEncodingChangeListener?.let { it(toTrackContext()) }
    }

    fun setOnTrackEncodingChangeListener(listener: ((TrackContext) -> Unit)?) {
        onTrackEncodingChangeListener = listener
    }

    fun setOnVadNotificationListener(listener: ((TrackContext) -> Unit)?) {
        onVadNotificationListener = listener
    }

    fun toTrackContext(): TrackContext {
        return TrackContext(track, peer, trackId, metadata, vadStatus, encoding, encodingReason, this)
    }
}
