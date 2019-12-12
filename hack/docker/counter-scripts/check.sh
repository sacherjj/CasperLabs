#!/bin/bash
BASE_PATH=../../../
CLIENT=$BASE_PATH/client/target/universal/stage/bin/casperlabs-client
PUBLIC_KEY=$(cat $BASE_PATH/hack/docker/keys/faucet-account/account-id-hex)
HOST="localhost"

set -e

RESPONSE=$(casperlabs-client --host $HOST show-blocks)

BLOCK_HASH=$(echo $RESPONSE | awk -F "block_hash: \"" '{print $2}' | awk -F "\" header" '{print $1}')

echo "Value of the 'count' in a Smart Contract named 'counter' deployed under $PUBLIC_KEY account."

$CLIENT --host $HOST query-state \
    --block-hash $BLOCK_HASH \
    --type address \
    --key $PUBLIC_KEY \
    --path "counter/count"
