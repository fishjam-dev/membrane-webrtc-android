package org.membraneframework.rtc.models

import org.membraneframework.rtc.utils.Metadata

data class Peer(var id: String, var metadata: Metadata, var trackIdToMetadata: Map<String, Metadata>)
