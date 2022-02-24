package org.membraneframework.rtc.models

import org.membraneframework.rtc.utils.Metadata

data class Peer(val id: String, val metadata: Metadata, val trackIdToMetadata: Map<String, Metadata>)
