package org.membraneframework.rtc

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import org.membraneframework.rtc.dagger.DaggerMembraneRTCComponent
import org.membraneframework.rtc.media.*
import org.membraneframework.rtc.models.RTCStats
import org.membraneframework.rtc.utils.Metadata
import org.membraneframework.rtc.utils.SerializedMediaEvent
import org.webrtc.Logging

/**
 * MembraneRTC client.
 * <p>
 * The client is responsible for relaying MembraneRTC Engine specific messages through given reliable transport layer.
 * Once initialized, the client is responsible for exchanging necessary messages via provided <strong>EventTransport</strong> passed via `ConnectOptions` and managing underlying
 * `PeerConnection`. The goal of the client is to be as lean as possible, meaning that all activities regarding the session such as moderating
 * should be implemented by the user himself on top of the <strong>MembraneRTC</strong>.
 * <p>
 * The user's ability of interacting with the client is greatly limited to the essential actions such as joining/leaving the session,
 * adding/removing local tracks and receiving information about remote peers and their tracks that can be played by the user.
 * <p>
 * User can request 3 different types of local tracks that will get forwarded to the server by the client:
 * <ul>
 *   <li>`LocalAudioTrack` - an audio track utilizing device's microphone</li>
 *   <li>`LocalVideoTrack` - a video track that can utilize device's camera or if necessary use video playback from a file (useful for testing with a simulator)</li>
 *   <li>`LocalScreencast` - a screencast track capturing a device's screen using <string>MediaProjection</strong> mechanism</li>
 * </ul>
 * <p>
 * It is recommended to request necessary audio and video tracks before joining the room but it does not mean it can't be done afterwards (in case of screencast)
 * <p>
 * Once the user received <strong>onConnected</strong> notification they can call the <strong>join</strong> method to initialize joining the session.
 * After receiving `onJoinSuccess` a user will receive notification about various peers joining/leaving the session, new tracks being published and ready for playback
 * or going inactive.
 */
class MembraneRTC
private constructor(
    private var client: InternalMembraneRTC
) {
    /**
     * Tries to join the RTC Engine. If user is accepted then onJoinSuccess will be called.
     * In other case {@link Callbacks.onJoinError} is invoked.
     * <p>
     * @param peerMetadata - Any information that other peers will receive in onPeerJoined
     * after accepting this peer
     */
    fun join(peerMetadata: Metadata) {
        client.join(peerMetadata)
    }

    /**
     * Disconnects the client.
     * <p>
     * Once the client gets disconnected it should not be reused. New client should be created instead.
     */
    fun disconnect() {
        client.disconnect()
    }

    /**
     * Feeds media event received from RTC Engine to MembraneWebRTC.
     * This function should be called whenever some media event from RTC Engine
     * was received and can result in MembraneWebRTC generating some other
     * media events.
     * @param mediaEvent - String data received over custom signalling layer.
     */
    fun receiveMediaEvent(mediaEvent: SerializedMediaEvent) {
        client.receiveMediaEvent(mediaEvent)
    }

    /**
     * Creates a video track utilizing device's camera.
     * <p>
     * The client assumes that the user has already granted camera permissions.
     *
     * @param videoParameters a set of target parameters such as camera resolution, frame rate or simulcast configuration
     * @param metadata the metadata that will be sent to the <strong>Membrane RTC Engine</strong> for media negotiation
     * @param captureDeviceName the name of the device to start video capture with, you can get device name by using
     * `LocalVideoTrack.getCaptureDevices` method
     * @return an instance of the video track
     */
    fun createVideoTrack(
        videoParameters: VideoParameters,
        metadata: Metadata,
        captureDeviceName: String? = null
    ): LocalVideoTrack {
        return client.createLocalVideoTrack(videoParameters, metadata, captureDeviceName)
    }

    /**
     * Creates an audio track utilizing device's microphone.
     * <p>
     * The client assumes that the user has already granted microphone recording permissions.
     *
     * @param metadata the metadata that will be sent to the <strong>Membrane RTC Engine</strong> for media negotiation
     * @return an instance of the audio track
     */
    fun createAudioTrack(metadata: Metadata): LocalAudioTrack {
        return client.createLocalAudioTrack(metadata)
    }

    /**
     * Creates a screen track recording the entire device's screen.
     * <p>
     * The method requires a media projection permission to be able to start the recording. The client assumes that the intent is valid.
     *
     * @param mediaProjectionPermission a valid media projection permission intent that can be used to starting a screen capture
     * @param videoParameters a set of target parameters of the screen capture such as resolution, frame rate or simulcast configuration
     * @param metadata the metadata that will be sent to the <strong>Membrane RTC Engine</strong> for media negotiation
     * @param onEnd callback that will be invoked once the screen capture ends
     * @return an instance of the screencast track
     */
    fun createScreencastTrack(
        mediaProjectionPermission: Intent,
        videoParameters: VideoParameters,
        metadata: Metadata,
        onEnd: (() -> Unit)? = null
    ): LocalScreencastTrack? {
        return client.createScreencastTrack(mediaProjectionPermission, videoParameters, metadata, onEnd)
    }

    /**
     * Removes an instance of local track from the client.
     *
     * @param trackId an id of a valid local track that has been created using the current client
     * @return a boolean whether the track has been successfully removed or not
     */
    fun removeTrack(trackId: String): Boolean {
        return client.removeTrack(trackId)
    }

    /**
     * Sets track encoding that server should send to the client library.
     *
     * The encoding will be sent whenever it is available.
     * If chosen encoding is temporarily unavailable, some other encoding
     * will be sent until chosen encoding becomes active again.
     *
     * @param trackId an id of a remote track
     * @param encoding an encoding to receive
     */
    fun setTargetTrackEncoding(trackId: String, encoding: TrackEncoding) {
        client.setTargetTrackEncoding(trackId, encoding)
    }

    /**
     * Enables track encoding so that it will be sent to the server.
     *
     * @param trackId an id of a local track
     * @param encoding an encoding that will be enabled
     */
    fun enableTrackEncoding(trackId: String, encoding: TrackEncoding) {
        client.enableTrackEncoding(trackId, encoding)
    }

    /**
     * Disables track encoding so that it will be no longer sent to the server.
     *
     * @param trackId and id of a local track
     * @param encoding an encoding that will be disabled
     */
    fun disableTrackEncoding(trackId: String, encoding: TrackEncoding) {
        client.disableTrackEncoding(trackId, encoding)
    }

    /**
     * Updates the metadata for the current peer.
     * @param peerMetadata Data about this peer that other peers will receive upon joining.
     *
     * If the metadata is different from what is already tracked in the room, the optional
     * callback `onPeerUpdated` will be triggered for other peers in the room.
     */
    fun updatePeerMetadata(peerMetadata: Metadata) {
        client.updatePeerMetadata(peerMetadata)
    }

    /**
     * Updates the metadata for a specific track.
     * @param trackId local track id of audio or video track.
     * @param trackMetadata Data about this track that other peers will receive upon joining.
     *
     * If the metadata is different from what is already tracked in the room, the optional
     * callback `onTrackUpdated` will be triggered for other peers in the room.
     */
    fun updateTrackMetadata(trackId: String, trackMetadata: Metadata) {
        client.updateTrackMetadata(trackId, trackMetadata)
    }

    /**
     * Updates maximum bandwidth for the track identified by trackId.
     * This value directly translates to quality of the stream and, in case of video, to the amount of RTP packets being sent.
     * In case trackId points at the simulcast track bandwidth is split between all of the variant streams proportionally to their resolution.
     * @param trackId track id of a video track
     * @param bandwidthLimit bandwidth in kbps
     */
    fun setTrackBandwidth(trackId: String, bandwidthLimit: TrackBandwidthLimit.BandwidthLimit) {
        client.setTrackBandwidth(trackId, bandwidthLimit)
    }

    /**
     * Updates maximum bandwidth for the given simulcast encoding of the given track.
     * @param trackId track id of a video track
     * @param encoding rid of the encoding
     * @param bandwidthLimit bandwidth in kbps
     */
    fun setEncodingBandwidth(
        trackId: String,
        encoding: String,
        bandwidthLimit: TrackBandwidthLimit.BandwidthLimit
    ) {
        client.setEncodingBandwidth(trackId, encoding, bandwidthLimit)
    }

    /**
     * Changes severity level of debug logs
     * @param severity enum value representing the logging severity
     */
    fun changeWebRTCLoggingSeverity(severity: Logging.Severity) {
        Logging.enableLogToDebugOutput(severity)
    }

    /**
     * Returns current connection stats
     * @return a map containing statistics
     */
    fun getStats(): Map<String, RTCStats> {
        return client.getStats()
    }

    companion object {
        /**
         * Creates an instance of <strong>MembraneRTC</strong> client.
         *
         * @param appContext the context of the current application
         * @param options a set of options defining parameters such as encoder parameters
         * @param listener a listener that will receive all notifications emitted by the <strong>MembraneRTC</strong>
         * @return an instance of the client in connecting state
         */
        fun create(appContext: Context, options: CreateOptions, listener: MembraneRTCListener): MembraneRTC {
            val ctx = appContext.applicationContext

            val component = DaggerMembraneRTCComponent
                .factory()
                .create(ctx)

            val client = component
                .membraneRTCFactory()
                .create(options, listener, Dispatchers.Default)

            return MembraneRTC(client)
        }
    }
}
