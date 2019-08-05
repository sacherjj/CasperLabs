# Standalone Execution Engine #

This binary provides a standalone version of execution engine (in contrast to grpc-server that will be used by the scala client). The purpose of it is to faciliate testing/debugging Wasm programs without the need to run gRPC server and client.

## Usage ##

First, you need to build the binary. Go to the `engine` root directory (where `Cargo.toml` lives) and run `cargo build`.

The command to run is `cargo run <wasm files> [--address <ADDRESS>]`. As of now `--address` is optional and not really used (note it has to go last, I didn't grok out why `clap` doesn't allow for positional arguments to go after flags).

`<wasm files>` is a list of Wasm files to execute. Programs will be executed in the order they were provided when calling `cargo run`.

Program prints out the Wasm file and result of running it.

### Example usage ###
```
cd execution-engine/engine
cargo build
// assuming we have step1.wasm and step2.wasm files
cargo run step1.wasm step2.wasm
```
