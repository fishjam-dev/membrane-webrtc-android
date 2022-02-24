package org.membraneframework.rtc

import android.content.Context
import kotlinx.coroutines.Dispatchers
import org.membraneframework.rtc.dagger.DaggerMembraneRTCComponent
import org.membraneframework.rtc.media.LocalAudioTrack
import org.membraneframework.rtc.media.LocalVideoTrack

public class MembraneRTC
private constructor(
    private var client: InternalMembraneRTC
) {

    public fun join() {
        client.join()
    }

    public fun disconnect() {
        client.disconnect()
    }

    public fun localVideoTrack(): LocalVideoTrack? {
        return client.localVideoTrack

    }

    public fun localAudioTrack(): LocalAudioTrack? {
        return client.localAudioTrack
    }

    companion object {
        fun connect(appContext: Context, options: ConnectOptions, listener: MembraneRTCListener): MembraneRTC {

            val ctx = appContext.applicationContext

            val component = DaggerMembraneRTCComponent
                .factory()
                .create(ctx)

            // TODO: make the dispatchers to be intejcted by the dagger itself
            val client = component
                .membraneRTCFactory()
                .create(options, listener, Dispatchers.Default)

            client.connect()

            return MembraneRTC(client)
        }
    }
}