# Push Maker

> A “vibe coding” side tool I built for my own manual testing workflow. Feel free to fork/tinker, but it ships as-is with zero guarantees.

Push Maker is a Compose Multiplatform desktop tool (macOS/Windows/Linux) that lets you craft push notification payloads and broadcast them to Android emulators or devices through ADB. It is designed to speed up manual regression testing by giving you a friendly GUI for payload editing, re-sending, and persistence across launches.

## Features

- **Device awareness** – automatically lists the devices returned by `adb devices -l`, lets you pick one, and refresh on demand.
- **Push builder** – compose notification metadata (title, body, channel, collapse key, priority, icon) plus arbitrary custom key/value fields without leaving the keyboard.
- **Send, save, repeat** – sending does not clear the form so you can replay the same payload; you can also save pushes for later and re-open them in a single click.
- **Persistent storage** – saved pushes are stored as JSON under the standard OS config directory (`~/Library/Application Support/PushMaker`, `%APPDATA%\PushMaker`, or `$XDG_CONFIG_HOME/PushMaker`).
- **ADB integration** – the app wraps `adb shell am broadcast` and sends the payload JSON via the `payload` extra using the `com.pushmaker.DEBUG_PUSH` action, so you can hook your receiver in the test app.
- **ADB aware** – it tries common SDK locations, lets you set the exact executable via the in-app settings dialog, and links you to the official Platform Tools download if nothing is detected.

## Requirements

- JDK 17+
- Android SDK platform tools available on your `PATH` (or via the `ADB` environment variable) so the app can invoke `adb`.

## Running locally

```bash
./gradlew :composeApp:run
```

The first launch will create a storage file for saved pushes and try to detect devices. Ensure at least one emulator or device is attached via `adb` to enable the send button.

### Minimum push requirements

- Select a target device from the toolbar.
- Provide **at least one** of Title or Body in the form. All other fields (channel, collapse key, icon, metadata, custom data) are optional and only enrich the payload.
- The **Broadcast action** field determines which Android receivers will get the intent (defaults to `com.pushmaker.DEBUG_PUSH`). Change it if your target app listens on a custom action.
- Optionally set **Target component** to force delivery to a specific receiver (format `package/.Receiver`).
- If ADB is not detected automatically, click the gear icon in the toolbar to point the app at your `adb` binary or open the Platform Tools download page.
- A friendly Name is only needed if you plan to save the push for reuse.

## Extending the push action

On the receiving Android app side, register a broadcast receiver for the `com.pushmaker.DEBUG_PUSH` action and read the `payload` string extra to parse the JSON. That JSON includes the notification core fields and two nested objects: `metadata` (notification-specific) and `data` (custom key/value fields).

### Ready-made Android test app

Need a quick receiver? Check out `../test-app`, a tiny Android project that listens for the (configurable) broadcast action and surfaces the payload in-app. Build/install it with `./gradlew :app:installDebug` from that directory, launch the **Push Maker Test** app once (this registers its in-app receiver), match the action, and optionally use the Target component field to lock broadcasts to `com.pushmaker.testapp/.PushMakerReceiver`.

### Manual adb command for debugging

Push Maker ultimately shells out to something like:

```
adb -s <device_serial> shell am broadcast \
    -a <broadcast_action> \
    [-n <package/.Receiver>] \
    --es payload '<json_payload>'
```

Populate the placeholders with the selected device serial, the action and optional component you entered, and the JSON payload (view via logs or export). Running this manually lets you confirm the receiver wiring before relying on the GUI.

## Packaging for desktop OSes

The Gradle build already declares native distributions (DMG/MSI/DEB). Run the packaging task on the corresponding OS and pick up the artifact from `composeApp/build/compose/binaries/main-release/`:

- **macOS** – `./gradlew :composeApp:packageReleaseDmg`
- **Windows** – `./gradlew :composeApp:packageReleaseMsi`
- **Linux** – `./gradlew :composeApp:packageReleaseDeb`

Each command bundles the JVM runtime and produces an installer you can share. For a portable archive (no installer) you can run `./gradlew :composeApp:createDistributable`, which places an app folder/zip under `composeApp/build/compose/binaries`. If you just need a runnable jar for any OS, use `./gradlew :composeApp:packageReleaseUberJarForCurrentOS`.
