BASE_PATH=../../../../
CLIENT=$BASE_PATH/client/target/universal/stage/bin/casperlabs-client
PUBLIC_KEY=$(cat $BASE_PATH/hack/docker/keys/faucet-account/account-id-hex)
SENDER_PRIVATE_KEY=$BASE_PATH/hack/docker/keys/faucet-account/account-private.pem
HOST="localhost"
