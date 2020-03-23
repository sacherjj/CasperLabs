#!/usr/bin/env bash

set -e

# stests will register aliases that normally cannot be used in scripts.
shopt -s expand_aliases

# Activate the stests commands.
source $HOME/.bashrc

# Get rid of the pushd/popd decorations.
source $STESTS_PATH_SH/utils.sh
# Make sure we get rid of them in the interactive session too.
cat >> $HOME/.bashrc <<- EOM
	source $STESTS_PATH_SH/utils.sh
EOM

echo 1.  Register network + faucet key:
stests-set-network $NETWORK
stests-set-network-faucet-key $NETWORK $KEYS/faucet-account/account-private.pem


# Switch to interactive mode.
exec bash
