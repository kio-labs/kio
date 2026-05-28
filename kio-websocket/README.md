# kio-websocket
websocket protocol implementation using [kio-async](https://github.com/kio-labs/kio-async).

> [!WARNING]
> This project is experimental and not intended for production use.
> It is mainly for learning, testing, and personal experiments.

It is a Kotlin port of [tsoding/cws](https://github.com/tsoding/cws). And this project is implemented step by step by following the tutorial in the video below:

https://www.youtube.com/watch?v=-PfG87485Po

The implementation has successfully passed test cases 1–7 from the [Autobahn Test Suite](https://github.com/crossbario/autobahn-testsuite)

## How to run
- native (Only supoort macos now): `./gradlew :example:async-echo-server:runDebugExecutableMacosArm64` 
