#!/usr/bin/env bash

set -o errexit

cargo build -p mint-token --target wasm32-unknown-unknown

cargo test -p casperlabs-engine-grpc-server -- --ignored
