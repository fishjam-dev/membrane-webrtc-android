# membrane-webrtc-android

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

## Installation
Add jitpack repo to your build.gradle:
```gradle
	allprojects {
		repositories {
			...
			maven { url 'https://jitpack.io' }
		}
	}
```

Add the dependency:
```gradle
	dependencies {
	  implementation 'com.github.membraneframework:membrane-webrtc-android:1.0.1'
	}
```

## Credits
This project is highly inspired by the [LiveKit](https://livekit.io/) project and their implementation of the [Android SDK](https://github.com/livekit/client-sdk-android) and reuses a lot of their implemented solutions (mainly dealing with WebRTC SDK while the signalling got completely replaced with an internal solution).

This project has been built and is maintained thanks to the support from [dscout](https://dscout.com/) and [Software Mansion](https://swmansion.com).

<img alt="expo sdk" height="100" src="./.github/dscout_logo.png"/>
<img alt="Software Mansion" src="https://logo.swmansion.com/logo?color=white&variant=desktop&width=150&tag=react-native-reanimated-github"/>
