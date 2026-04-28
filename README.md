# jarvis-mini-android

Kotlin and Jetpack Compose Android client for Jarvis Mini. Listens for the wake word "Hey Jarvis", records a command, sends it to [jarvis-mini-server](https://github.com/akashpatel1198/jarvis-mini-server), speaks the reply aloud, and performs any phone-side actions the server returns.

## Setup

Requires Android Studio (Hedgehog or newer) and JDK 17+.

```bash
git clone https://github.com/akashpatel1198/jarvis-mini-android.git
cd jarvis-mini-android
```

Add your server URL to `local.properties` (already gitignored):

```
serverUrl=http://YOUR_SERVER_IP:8000
```

If unset, the build falls back to `http://10.0.2.2:8000`, which is the host machine from an Android emulator's perspective.

Open the project in Android Studio, plug in a device or start an emulator, and hit Run. Or from the CLI:

```bash
./gradlew installDebug
```

## Permissions

The app requests `RECORD_AUDIO` and `POST_NOTIFICATIONS`, and runs a foreground microphone service for wake-word detection. Cleartext HTTP is enabled because the server normally lives on the local network.

## Wake word

Wake-word detection uses [openWakeWord](https://github.com/dscripka/openWakeWord) ONNX models bundled in `app/src/main/assets/oww/`. The default model is "Hey Jarvis". Swap the ONNX file and update `WakeWordDetector` to use a different one.

## Spotify integration

Playback control uses the Spotify App Remote SDK against the official Spotify app on the phone, so the Spotify app must be installed and signed in. No Spotify keys are baked into this repo.

## Build commands

```bash
./gradlew assembleDebug   # build debug APK
./gradlew installDebug    # install on connected device
./gradlew test            # unit tests
./gradlew lint            # Android lint
```

## Adding your own phone action

`PhoneAction.kt` defines the action types the client knows how to perform. To add one:

1. Add a new variant in `PhoneAction.kt`, parsed from the JSON the server sends.
2. Handle it where phone actions are dispatched, in `JarvisService` and `MainActivity`.
3. Have a server-side tool return that payload as its `phone_action`.

## Contributing

Issues and PRs welcome. If wake word fails on your device, an action doesn't dispatch, or you've built an integration you'd like to share, open an issue or send a PR.
