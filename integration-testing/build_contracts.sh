#!/usr/bin/env bash

set -o errexit

ARCH="wasm32-unknown-unknown"
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
DEST_DIR="${DIR}/resources"
CARGO_FLAGS="-Z unstable-options"

# This is necessary for CI
source "${HOME}/.cargo/env"

# Path to execution engine relative to this script
EE_DIR="${DIR}/../execution-engine"

# Rust toolchain file
RUST_TOOLCHAIN="$(<${EE_DIR}/rust-toolchain)"

# This is also necessary for CI
if ! rustup toolchain list | grep -q -c "$RUST_TOOLCHAIN"; then
    rustup toolchain install $RUST_TOOLCHAIN
fi

# This is also necessary for CI
if ! rustup target list --toolchain "$RUST_TOOLCHAIN" --installed | grep -q -c "$ARCH"; then
    rustup target add --toolchain $RUST_TOOLCHAIN $ARCH
fi

# Expand all integration test contracts into array in a similar way
# `execution-engine/Makefile` does it.
INTEGRATION_CONTRACTS=(${EE_DIR}/contracts/integration/*/)

# Extra contracts that are not part of ./integration/ contracts but are
# used by the integration test suite.
INTEGRATION_CONTRACTS+=(
    "${EE_DIR}/contracts/client/standard-payment"
    "${EE_DIR}/contracts/test/pos-bonding"
    "${EE_DIR}/contracts/test/endless-loop"
)

# Build all integration test contracts
for dir in "${INTEGRATION_CONTRACTS[@]}"; do
    if [ ! -f "$dir/Cargo.toml" ]; then
        continue
    fi

    pushd $dir > /dev/null

    cargo build $CARGO_FLAGS --target $ARCH --release --out-dir $DEST_DIR

    popd > /dev/null

done
