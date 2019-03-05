# COMM #

This module implements a communication layer for execution engine. It is using gRPC library. Definitions for objects and services come from the `ipc.proto` file that is shared between Scala and Rust subprojects. Rust implements the server part of the gRPC services while Scala implements a client.

## How to run ##

In the root directory of the `comm` project run `cargo run --bin casperlabs-engine-grpc-server <socket>` where `<socket>` is the path to the socket file used for communicating between client and the server.

Building `comm` requires that the [Protocol Buffers compiler](https://github.com/protocolbuffers/protobuf) `protoc` is installed and in `$PATH`.
