# WebRTC Android guide

Currecntly we're using the fork of Chromium's WebRTC library from here: 

## Initial setup

Compiling webrtc for Android works only on Linux, so if you're using Mac, use for example Docker. I've used circleci's `cimg/android` image. Run the following commands:
```
git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git
export PATH=/home/circleci/project/depot_tools:$PATH # or whatever path you have depot_tools in 
```
to install Chromium depot tools.

If you want to compile the fork, change the address of the repo in `depot_tools/fetch_configs/webrtc.py` from `'https://webrtc.googlesource.com/src.git'` to `'https://github.com/webrtc-sdk/webrtc.git'`. Depot tools will now automatically handle cloning the fork and dependencies.

Pro tip if you're using docker: you can attach VSCode to docker container and edit files there as if they were on your local machine using Docker extension.

Then create the new directory and fetch the repo there:
```
mkdir webrtc-checkout
cd webrtc-checkout/
fetch --nohooks webrtc_android
gclient sync
```

## Compiling
Then you can build the aar for Android with this command:
```
cd src
./tools_webrtc/android/build_aar.py --build-dir webrtc_android --output ./webrtc_android/libwebrtc.aar --arch arm64-v8a --extra-gn-args 'is_java_debug=true rtc_include_tests=false rtc_use_h264=false is_component_build=false use_rtti=true rtc_build_examples=false treat_warnings_as_errors=false'
```

Note that there is only one architecture for quicker compilation, specify more if needed.

Also I have a problem that sometimes compilation freezes (maybe it's specific for my configuration). Just kill it and try again - you won't lose progress. If you keep webrtc_android folder, the next compilations will be quicker.

## Adding compiled library to the project

After compiling add it to Android project. In `build.gradle` in MembraneRTC module replace `api 'com.github.webrtc-sdk:android:104.5112.01'` with `api files('./libs/libwebrtc.aar')`. Then copy aar from docker to the project like this:
`docker cp container_name:/home/circleci/project/src/webrtc_android/libwebrtc.aar path/to/project/membrane-webrtc-android/MembraneRTC/libs/libwebrtc.aar`

To enable logs from cpp into logcat add `Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO)` after initializing `PeerConnectionFactory`.

## A handy script for compiling & copying
```
#!/bin/bash
docker exec -t webrtc_fork sh -c "export PATH=/home/circleci/project/depot_tools:$PATH && cd /home/circleci/project/webrtc-checkout/src && ./tools_webrtc/android/build_aar.py --build-dir webrtc_android --output ./webrtc_android/libwebrtc.aar --arch arm64-v8a --extra-gn-args 'is_java_debug=true rtc_include_tests=false rtc_use_h264=false is_component_build=false use_rtti=true rtc_build_examples=false treat_warnings_as_errors=false'"
docker cp webrtc_fork:/home/circleci/project/webrtc-checkout/src/webrtc_android/libwebrtc.aar /Users/angelikaserwa/Projects/membrane-webrtc-android/MembraneRTC/libs/libwebrtc.aar
```