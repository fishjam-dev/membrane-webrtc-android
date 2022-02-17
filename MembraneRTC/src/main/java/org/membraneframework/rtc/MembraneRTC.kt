package org.membraneframework.rtc

import kotlinx.coroutines.*
import org.membraneframework.rtc.events.*
import org.membraneframework.rtc.models.Peer
import org.membraneframework.rtc.transport.EventTransport
import org.membraneframework.rtc.transport.EventTransportError
import org.membraneframework.rtc.transport.EventTransportListener
import org.membraneframework.rtc.utils.ClosableCoroutineScope
import org.membraneframework.rtc.utils.Metadata
import timber.log.Timber

public class MembraneRTC constructor(
    private val connectOptions: ConnectOptions,
    private val listener: MembraneRTCListener,
    private val defaultDispatcher: CoroutineDispatcher
): EventTransportListener {
    public data class ConnectOptions(val transport: EventTransport, val config: Metadata)

    private var transport: EventTransport = connectOptions.transport

    private val localPeer: Peer = Peer(id = "", metadata = connectOptions.config, trackIdToMetadata = mapOf())

    // mapping from peer's id to the peer himself
    private val remotePeers = HashMap<String, Peer>()

    private val coroutineScope: CoroutineScope = ClosableCoroutineScope(SupervisorJob() + defaultDispatcher)

    fun connect() {
        coroutineScope.async {
            try {
                transport.connect(this@MembraneRTC)
                listener.onConnected()
            } catch(e: Exception) {
                // TODO: add better exception handling
                Timber.i(e, "Failed to connect")
            }
        }
    }

    fun disconnect() {
        coroutineScope.async {
            transport.disconnect()
        }
    }

    fun join() {
        coroutineScope.async {
            transport.send(Join(localPeer.metadata))
        }
    }

    companion object {
        fun connect(options: ConnectOptions, listener: MembraneRTCListener): MembraneRTC {
            // TODO: this should be injected...
            val client = MembraneRTC(options, listener, Dispatchers.Default)

            client.connect()

            return client
        }
    }

    override fun onEvent(event: ReceivableEvent) {
        when (event) {
            is PeerAccepted -> {
                localPeer.id = event.data.id

                event.data.peersInRoom.forEach {
                    Timber.i("Peer in room $it")
                }

                listener.onJoinSuccess(localPeer.id, peersInRoom = event.data.peersInRoom)
            }

            is PeerDenied -> {
                // TODO: return meaningful data
                listener.onJoinError(mapOf<String, String>())
            }

            is PeerJoined -> {
                val peer = event.data.peer
                if (peer.id == this.localPeer.id) {
                    return
                }

                remotePeers[peer.id] = peer

                listener.onPeerJoined(peer)
            }

            is PeerLeft -> {
                remotePeers.remove(event.data.peerId)?.let {
                    // TODO: add all tracks handling here

                    listener.onPeerLeft(it)
                }
            }

            is PeerUpdated -> {
                remotePeers[event.data.peerId]?.let {
                    // TODO: check if that is immutable or needs explicit replacing in a list...
                    it.metadata = event.data.metadata
                }
            }

            is OfferData -> {
                coroutineScope.async {
                    onOfferData(event)
                }
            }

            is SdpAnswer -> {
                coroutineScope.async {
                    onSdpAnswer(event)
                }
            }

            is RemoteCandidate -> {
                coroutineScope.async {
                    onRemoteCandidate(event)
                }
            }

            is TracksAdded -> {
                TODO("TracksAdded")
            }

            is TracksRemoved -> {
                TODO("TracksRemoved")
            }

            is TrackUpdated -> {
                TODO("TrackUpdated")
            }

            else ->
                Timber.e("Failed to process unknown event: $event")
        }

        Timber.i("MembraneRTC received event: ${event.toString()}")
    }

    override fun onError(error: EventTransportError) {
        print("MembraneRTC transport error...")
    }

    override fun onClose() {
        print("MembraneRTC closed...")
    }

    fun onOfferData(offerData: OfferData) {
    }

    fun onSdpAnswer(sdpAnswer: SdpAnswer) {
    }

    fun onRemoteCandidate(remoteCandidate: RemoteCandidate) {
    }
}