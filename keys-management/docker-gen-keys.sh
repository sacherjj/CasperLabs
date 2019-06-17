#!/bin/sh
set -euo pipefail

# Generates all necessary keys for a node using Docker image
# Usage:
# ./docker-gen-keys.sh <directory where to put keys>

if [[ "$1" == /* ]] || [[ "$1" == ~* ]]; then
	OUTPUT_DIR="$1"
else
	OUTPUT_DIR="$(PWD)/$1"	
fi

if [ ! -d "$OUTPUT_DIR" ]; then
    echo "ERROR: output dir doesn't exist"
    echo "usage: ./docker-gen-keys.sh <dir>"
    exit 1
fi

docker pull casperlabs/keys-generator
docker run --rm -it -v "$OUTPUT_DIR":/keys casperlabs/keys-generator /keys