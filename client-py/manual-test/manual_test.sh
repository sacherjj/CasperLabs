#!/usr/bin/env bash

# Stand up highway network
pushd ../../hack/docker || exit
make up-all
popd || exit

mkdir account
casperlabs_client keygen ./account

mkdir validator
casperlabs_client validator-keygen ./validator

read -r -p 'Press [Enter] to continue'

rm account/*
rmdir account

rm validator/*
rmdir validator

# Shutdown highway network
pushd ../../hack/docker || exit
make down
popd || exit
