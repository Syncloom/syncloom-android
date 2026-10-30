# syncloom-android

The Android app (Kotlin, Jetpack Compose, Material 3) with a `DocumentsProvider`.

Part of [Syncloom](https://github.com/Syncloom), a self-hosted, end-to-end encrypted file sync and key/value store. Status: **public preview (0.1)**.

```bash
(cd ../syncloom-core && ./build-android.sh)
./gradlew testDebugUnitTest assembleDebug
```
The APK builds and a JVM test drives the real core against a running server. The UI and the document provider have not been tested on a device or emulator yet.

## Working with the other repositories

The Syncloom repositories reference each other by relative path. Clone the ones you need **side by side in one folder**, for example `syncloom-core`, `syncloom-server` and `syncloom-web` next to each other.

## License

Apache License 2.0, see [LICENSE](LICENSE).
