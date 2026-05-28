# kio

Experimental Kotlin Multiplatform libraries for async I/O development.

> [!WARNING]
> This project is experimental and not intended for production use.
> It is mainly for learning, testing, and personal experiments.


## Overview

`kio` is a collection of Kotlin Multiplatform modules focused on low-level asynchronous I/O and protocol implementation.

The repository currently contains:

* `kio-async` — coroutine-friendly async I/O primitives built on top of `kotlinx-io`
* `kio-websocket` — WebSocket protocol implementation based on `kio-async`
* `kio-postgres` — PostgreSQL wire protocol, connection, and type encoding/decoding experiments

## Modules

### kio-async

Core async I/O module.

### kio-websocket

WebSocket implementation built on top of `kio-async`.

### kio-postgres

PostgreSQL-related modules:

* `kio-postgres:protocol` — PostgreSQL frontend/backend protocol messages
* `kio-postgres:types` — PostgreSQL type representations and binary/text encoding support
* `kio-postgres:conn` — connection-level client logic

