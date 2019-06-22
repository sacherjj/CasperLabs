#!/usr/bin/env bash

set -o errexit

CONTRACTS=(
    "mint-token"
    "transfer-to-account-01"
    "transfer-to-account-02"
)

for CONTRACT in "${CONTRACTS[@]}"; do
    ~/.cargo/bin/cargo build -p "${CONTRACT}" --target wasm32-unknown-unknown
done

~/.cargo/bin/cargo test -p casperlabs-engine-grpc-server -- --ignored --nocapture
