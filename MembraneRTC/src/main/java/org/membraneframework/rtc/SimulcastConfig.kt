package org.membraneframework.rtc

/**
 * Enum describing possible track encodings.
 * `"h"` - original encoding
 * `"m"` - original encoding scaled down by 2
 * `"l"` - original encoding scaled down by 4
 */
enum class TrackEncoding(val rid: String) {
    L("l"), M("m"), H("h");
}

/**
 * Simulcast configuration.
 *
 * At the moment, simulcast track is initialized in three versions - low, medium and high.
 * High resolution is the original track resolution, while medium and low resolutions
 * are the original track resolution scaled down by 2 and 4 respectively.
 */
data class SimulcastConfig(
    /**
     * Whether to simulcast track or not.
     */
    val enabled: Boolean,
    /**
     * List of initially active encodings.
     *
     * Encoding that is not present in this list might still be
     * enabled using {@link enableTrackEncoding}.
     */
    val activeEncodings: List<TrackEncoding> = listOf()
)
