# Building

## GitHub Actions (recommended)
Every push builds the APK (`.github/workflows/android.yml`). Pushes to `main` also publish a GitHub Release:
<https://github.com/radovanbauer/ounce/releases/latest/download/ounce.apk>

Builds are signed with the keystore in the repository secret `SIGNING_KEYSTORE_B64` (base64 of a keystore with
alias `androiddebugkey`, password `android`), so every build installs as an update of the previous one. Local builds
use the same key when `~/.android-docker/ounce-signing.keystore` exists. Keep a backup of that keystore: without it,
existing installs can't be updated.

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
