#!/usr/bin/env bash

set -o errexit

ARCH="wasm32-unknown-unknown"
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
SOURCE_DIR="${DIR}/target/${ARCH}/debug"
DEST_DIR="${DIR}/../resources"

# This is necessary for CI
source "${HOME}/.cargo/env"

rustup target add --toolchain $(cat rust-toolchain) "${ARCH}"

cargo build --target "${ARCH}"

WASMS=($(find "${SOURCE_DIR}" -name \*.wasm))

for FILE in "${WASMS[@]}"; do
    cp -v "${FILE}" "${DEST_DIR}"
done
