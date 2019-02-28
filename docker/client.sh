#!/usr/bin/env sh

set -e

# Deploy a WASM contract by connecting to the docker network using the client image.
# See https://slack-files.com/TDVFB45LG-FFBGDQSBW-bad20239ec

# usage: ./client.sh <node-id> <command> [OPTION...]
# for example:
#
# ./client.sh node-0 deploy $PWD/../../contract-examples/hello-name/define/target/wasm32-unknown-unknown/release \
#     --from 00000000000000000000 \
#     --gas-limit 100000000 --gas-price 1 \
#     --session /data/helloname.wasm \
#     --payment /data/helloname.wasm

if [ $# -lt 2 ]; then
    echo "usage: ./client.sh <node-id> <command> [OPTION...]" && exit 1
fi

NODE=$1; shift
CMD=$1; shift

case "$CMD" in
    "deploy")
        # Need to mount the files.
        VOL=$1; shift
        docker run --rm \
            --network casperlabs \
            --volume $VOL:/data \
            casperlabs/client:latest \
            --host $NODE --port 40401 deploy $@
        ;;

    --*)
        # --help doesn't like --host and --port
        docker run --rm \
            --network casperlabs \
            casperlabs/client:latest \
            $CMD
        ;;

    *)
        docker run --rm \
            --network casperlabs \
            casperlabs/client:latest \
            --host $NODE --port 40401 $CMD $@
        ;;
esac
