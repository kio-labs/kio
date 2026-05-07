## WebSocket implementation based on [kotlinx-io](https://github.com/Kotlin/kotlinx-io)

This project is for study purposes only.

It is a Kotlin port of [tsoding/cws](https://github.com/tsoding/cws). And this project is implemented step by step by following the tutorial in the video below:

https://www.youtube.com/watch?v=-PfG87485Po

The implementation has successfully passed test cases 1–7 from the [Autobahn Test Suite](https://github.com/crossbario/autobahn-testsuite)

## How to run
### Blocking IO server
- jvm: `./gradlew :example:echo-server:jvmRun`
- native (Only supoort macos now): `./gradlew :example:echo-server:runDebugExecutableMacosArm64` 

### Async IO server
- native (Only supoort macos now): `./gradlew :example:async-echo-server:runDebugExecutableMacosArm64` 
