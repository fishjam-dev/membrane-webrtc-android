package org.membraneframework.rtc.models

import org.membraneframework.rtc.SimulcastConfig
import org.membraneframework.rtc.utils.Metadata

data class TrackData(val metadata: Metadata?, val simulcastConfig: SimulcastConfig?){

}
