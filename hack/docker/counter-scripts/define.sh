#!/bin/bash
BASE_PATH=../../../
CLIENT=$BASE_PATH/client/target/universal/stage/bin/casperlabs-client
COUNTER_DEFINE_WASM=$BASE_PATH/execution-engine/target/wasm32-unknown-unknown/release/counter_define.wasm
SENDER_PRIVATE_KEY=$BASE_PATH/hack/docker/keys/faucet-account/account-private.pem
HOST="localhost"

set -e

RESPONSE=$($CLIENT --host $HOST deploy \
    --private-key $SENDER_PRIVATE_KEY \
    --payment-amount 10000000 \
    --session $COUNTER_DEFINE_WASM
)

DEPLOY_HASH=$(echo $RESPONSE | awk '{print $3}')

echo "Deployed with hash $DEPLOY_HASH"

$CLIENT --host $HOST propose

echo "Deploy status:"

$CLIENT --host $HOST show-deploy $DEPLOY_HASH

./check.sh
