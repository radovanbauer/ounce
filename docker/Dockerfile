# Android build toolchain (JDK 17 + SDK 35), used by build.sh for local builds.
FROM eclipse-temurin:17-jdk
RUN apt-get update && apt-get install -y --no-install-recommends unzip curl ca-certificates && rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/opt/android-sdk
RUN mkdir -p $ANDROID_HOME/cmdline-tools \
    && curl -fsSL -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
    && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
    && rm /tmp/tools.zip \
    && yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null \
    && $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" >/dev/null
WORKDIR /work
