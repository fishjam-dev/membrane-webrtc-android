# membrane-at-dscout-poc-android
Android Proof-Of-Concept for Membrane SFU

## Components
The repository consists of 2 components:
- `MembraneRTC` - Standalone Membrane WebRTC client fully compatible with `Membrane RTC Engine` bases on a [web version](https://github.com/membraneframework/membrane_rtc_engine)
    responsible for exchanging media events and receiving media streams which then can be presented to the users
- `app` - Demo application utilizing `MembraneRTC` client

### MembraneRTC
The main goal of the client was to be as similar to web version as possible.
Just like with web client, the native mobile client is pretty raw. It is as low level as possible without exposing any of WebRTC details.
It is user's responsibility to keep track of all peers in the room and their corresponding tracks. The client's responsibility is just to
notify the user about all the changes regarding the underlying session with the backend server.

What user needs to do is just to provide config necessary for client connection, create local tracks (audio, video or screencast)
start the client and listen for any changes via `MembraneRTCListener` interface.

### app
The demo application consists of 2 activities:
- `MainActivity.kt` - a room joining screen with 2 inputs for room's name, and participant's display name, and a single join button
    that when pressed will start the the room activity
- `RoomActivity.kt` - the proper room's activity with participant's controls and participants' video feeds

The user has the following control buttons at hand:
- microphone mute/unmute toggle
- camera video mute/unmute toggle
- leave call button
- front/back camera switch
- screencast toggle

## Necessary setup
The only required constant is the media server's URL that can be found at the top of `MainActivity.kt`.
