#!/usr/bin/env bash

# Stand up highway network
pushd ../../hack/docker || exit
make node-0/up
make node-1/up
make node-2/up
popd || exit

mkdir account
casperlabs_client keygen ./account

mkdir validator
casperlabs_client validator-keygen ./validator

echo "### show-peers ###"
casperlabs_client -h 127.0.0.1 -p 40401  show-peers

echo "### show-blocks ###"
casperlabs_client -h 127.0.0.1 -p 40401 show-blocks -d 3


# read -r -p 'Press [Enter] to teardown'

rm account/*
rmdir account

rm validator/*
rmdir validator

# Shutdown highway network
pushd ../../hack/docker || exit
#make node-0/down
#make node-1/down
#make node-2/down
popd || exit
