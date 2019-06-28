#!/usr/bin/env bash

set -o errexit

ARCH="wasm32-unknown-unknown"
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
SOURCE_DIR="${DIR}/target/${ARCH}/release"
DEST_DIR="${DIR}/../resources"
CARGO_FLAGS="-Z unstable-options"

# This is necessary for CI
source "${HOME}/.cargo/env"

# This is also necessary for CI
rustup target add --toolchain $(cat "${DIR}/rust-toolchain") $ARCH

pushd $DIR

cargo build $CARGO_FLAGS --target $ARCH --release --out-dir $DEST_DIR

popd
