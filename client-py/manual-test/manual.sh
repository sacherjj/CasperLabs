#!/usr/bin/env bash

mkdir account
casperlabs_client keygen ./account

mkdir validator
casperlabs_client validator-keygen ./validator

echo "### show-peers ###"
casperlabs_client -h 127.0.0.1 -p 40401  show-peers

echo "### show-blocks ###"
casperlabs_client -h 127.0.0.1 -p 40401 show-blocks -d 3

echo "## transfer ###"
casperlabs_client transfer --amount 10000 --target ./account/account-public.pem --from ../../hack/docker/keys/account-0/account-public.pem --private-key ../../hack/docker/keys/account-0/account-private.pem


read -r -p 'Press [Enter] to teardown'

rm account/*
rmdir account

rm validator/*
rmdir validator
