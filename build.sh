#!/usr/bin/env bash
# Build the Ounce debug APK in a container and (optionally) install it over (wifi) adb.
#   ./build.sh                 build only
#   ./build.sh install         build + install on the connected phone
#   ./build.sh adb <args...>   run adb, e.g. "adb pair 192.168.1.50:37123 123456", "adb connect 192.168.1.50:41234"
# A build needs ~2.5 GB RAM; see BUILDING.md.
set -euo pipefail
cd "$(dirname "$0")"
IMG=ounce-android-builder
ADB_CTR=ounce-adb
mkdir -p "$HOME/.cache/ounce-gradle" "$HOME/.android-docker"
docker image inspect "$IMG" >/dev/null 2>&1 || docker build -t "$IMG" docker/

# Hard caps so a build can't push a shared machine into swap: no swap use, 2 CPUs, low priority.
LIMITS=(--memory=2560m --memory-swap=2560m --cpus=2 --cpu-shares=256)
RUN=(nice -n 15 docker run --rm "${LIMITS[@]}" --network host -v "$PWD":/work -w /work
     -v "$HOME/.cache/ounce-gradle":/root/.gradle -v "$HOME/.android-docker":/root/.android
     -e ANDROID_HOME=/opt/android-sdk "$IMG")

# A long-running adb server container keeps wifi pairing/connections alive between commands.
ensure_adb() {
  if ! docker ps --format '{{.Names}}' | grep -q "^$ADB_CTR\$"; then
    docker rm -f "$ADB_CTR" >/dev/null 2>&1 || true
    docker run -d --name "$ADB_CTR" --network host -v "$HOME/.android-docker":/root/.android \
      -v "$PWD":/work "$IMG" /opt/android-sdk/platform-tools/adb -a nodaemon server start >/dev/null
    sleep 2
  fi
}
adb() { ensure_adb; docker exec "$ADB_CTR" /opt/android-sdk/platform-tools/adb "$@"; }

case "${1:-build}" in
  adb) shift; adb "$@"; exit ;;
esac
avail_mb=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
if [ "$avail_mb" -lt 2800 ] && [ -z "${FORCE_BUILD:-}" ]; then
  echo "Only ${avail_mb} MB RAM available; a build needs ~2.5 GB. Build elsewhere (see BUILDING.md) or set FORCE_BUILD=1." >&2
  exit 1
fi
"${RUN[@]}" bash -c 'echo "sdk.dir=/opt/android-sdk" > local.properties && ./gradlew --no-daemon -q assembleDebug'
cp app/build/outputs/apk/debug/app-debug.apk ounce.apk
echo "Built $(pwd)/ounce.apk"
if [ "${1:-}" = install ]; then
  adb install -r /work/ounce.apk
  adb shell am start -n com.radovanbauer.ounce/.ui.MainActivity >/dev/null
fi
