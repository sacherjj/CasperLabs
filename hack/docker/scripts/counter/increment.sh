#!/bin/bash

set -e
source $(dirname $0)/common.sh

COUNTER_CALL_WASM=$BASE_PATH/execution-engine/target/wasm32-unknown-unknown/release/counter_call.wasm

RESPONSE=$($CLIENT --host $HOST deploy \
    --private-key $SENDER_PRIVATE_KEY \
    --payment-amount 10000000 \
    --session $COUNTER_CALL_WASM
)

DEPLOY_HASH=$(echo $RESPONSE | awk '{print $3}')

echo "Deployed with hash $DEPLOY_HASH"

$CLIENT --host $HOST propose

echo "Deploy status:"

$CLIENT --host $HOST show-deploy $DEPLOY_HASH

./check.sh
