# COMM #

This module implements a communication layer for execution engine. It is using gRPC library. Definitions for objects and services comes from the `ipc.proto` file that is shared between Scala and Rust subprojects. Rust implements the server part of the gRPC services while Scala implements a client.

## How to build ##

First, you need to build protoc files. These are the definitions of the communication protocol. Go to the root directory of the project (where the `Cargo.toml` file is located) and run `cargo run --bin grpc-protoc`. This will take a `ipc.proto` file from `$root/models/src/main/protobuf` directory and generate `ipc.rs` and `ipc_grpc.rs` file that contain models and services respectively.

`protoc-rust-grpc` requires that `protoc` is installed on the machine.

Now, we can run the server. Again, in the root directory of the `comm` project run `cargo run --bin casperlabs-engine-grpc-server <socket>` where `<socket>` is the path to the socket file used for communicating between client and the server.
