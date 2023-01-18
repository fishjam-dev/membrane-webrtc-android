package org.membraneframework.rtc.models

import org.membraneframework.rtc.TrackEncoding
import org.membraneframework.rtc.media.RemoteTrack
import org.membraneframework.rtc.utils.Metadata

fun interface OnTrackEncodingChangeListener {
    fun onTrackEncodingChange(trackContext: TrackContext)
}

fun interface OnVadNotificationListener {
    fun onVadNotification(trackContext: TrackContext)
}

class TrackContext(track: RemoteTrack?, val peer: Peer, val trackId: String, metadata: Metadata) {
    private var onTrackEncodingChangeListener: (OnTrackEncodingChangeListener)? = null
    private var onVadNotificationListener: (OnVadNotificationListener)? = null

    var track: RemoteTrack? = track
        internal set
    var metadata: Metadata = metadata
        internal set

    var vadStatus: VadStatus = VadStatus.SILENCE
        internal set(value) {
            field = value
            onVadNotificationListener?.let { onVadNotificationListener?.onVadNotification(this) }
        }

    var encoding: TrackEncoding? = null
        private set
    var encodingReason: EncodingReason? = null
        private set

    internal fun setEncoding(encoding: TrackEncoding, encodingReason: EncodingReason) {
        this.encoding = encoding
        this.encodingReason = encodingReason
        onTrackEncodingChangeListener?.let { onTrackEncodingChangeListener?.onTrackEncodingChange(this) }
    }

    fun setOnTrackEncodingChangeListener(listener: OnTrackEncodingChangeListener?) {
        onTrackEncodingChangeListener = listener
    }

    fun setOnVadNotificationListener(listener: OnVadNotificationListener?) {
        onVadNotificationListener = listener
    }
}
