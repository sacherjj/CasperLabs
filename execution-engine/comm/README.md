# COMM #

This module implements a communication layer for execution engine. It is using gRPC library. Definitions for objects and services comes from the `ipc.proto` file that is shared between Scala and Rust subprojects. Rust implements the server part of the gRPC services while Scala implements a client.

## How to build ##

Go to the root directory of the project (where the `Cargo.toml` file is located) and run `cargo run`. This will take a `ipc.proto` file from `$root/models/src/main/protobuf` directory and generate `ipc.rs` and `ipc_grpc.rs` file that contain models and services respectively.

`protoc-rust-grpc` requires that `protoc` is installed on the machine. 
