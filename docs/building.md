# Building

```bash
export ANDROID_HOME=<sdk>   # platform android-37, build-tools 36+
./gradlew assembleDebug
```

Gradle 9.8, AGP 9.4 with built-in Kotlin, Java 21. Signed releases: [RELEASING.md](../RELEASING.md).

Instrumented tests on an emulator or on Firebase Test Lab's phones (Samsung, OnePlus, OPPO, Motorola): [testlab.md](testlab.md).

Where state moves through steps (the dictation, the keyboard's hold, the handoff's flight, onboarding), and which flags stay flags: [state-machines.md](state-machines.md).
