#!/usr/bin/env bash

mkdir account_ed25519
casperlabs_client keygen ./account_ed25519

mkdir account_secp256k1
casperlabs_client keygen --algorithm SECP256K1 ./account_secp256k1

mkdir validator
casperlabs_client validator-keygen ./validator


echo
echo "### show-peers ###"
echo
casperlabs_client -h 127.0.0.1 -p 40401  show-peers

echo
echo "### show-blocks ###"
echo
casperlabs_client -h 127.0.0.1 -p 40401 show-blocks -d 12

echo
echo "### vdag ###"
echo
casperlabs_client -h 127.0.0.1 -p 40401 vdag -d 4


read -r -p 'Press [Enter] to teardown'

rm account_ed25519/*
rmdir account_ed25519

rm account_secp256k1/*
rmdir account_secp256k1

rm validator/*
rmdir validator
