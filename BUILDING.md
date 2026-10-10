# Building

## GitHub Actions (recommended)
Every push builds the debug APK (`.github/workflows/android.yml`); download the `ounce-apk` artifact from the run.

To make CI builds install as updates of your local builds, add a repository secret `DEBUG_KEYSTORE_B64` containing
your debug keystore: `base64 -w0 ~/.android-docker/debug.keystore` (or `~/.android/debug.keystore`).

## Locally
Requirements: Docker and ~2.5 GB of free RAM.

```bash
./build.sh            # builds ounce.apk in a container (JDK 17 + Android SDK 35)
./build.sh install    # builds and installs on a phone connected via adb
```

The container is capped at 2.5 GB RAM / 2 CPUs at low priority, and the script refuses to start when less than
~2.8 GB is available (`FORCE_BUILD=1` overrides), so it can run on a machine that also hosts other services.

### Installing over wifi
On the phone: Settings › System › Developer options › Wireless debugging › Pair device with pairing code.
```bash
./build.sh adb pair <ip>:<pairing-port> <code>   # once
./build.sh adb connect <ip>:<port>               # the port shown on the Wireless debugging screen
./build.sh install
```
