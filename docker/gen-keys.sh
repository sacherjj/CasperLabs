#!/usr/bin/env bash
set -euo pipefail
trap "echo 'error: Script failed: see failed command above'" ERR
# Generates all necessary key for a node.
# Usage:
# ./gen-keys.sh <directory where to put keys>
#
# Example:
# ./gen-keys.sh test-dir
#
# Will produce:
#
# $ ls test-dir
# node-id               # node ID as in casperlabs://c0a6c82062461c9b7f9f5c3120f44589393edf31@<NODE ADDRESS>?protocol=40400&discovery=40404
#                       # derived from node.key.pem
# node.certificate.pem  # TLS certificate used for node-to-node interaction encryption
#                       # derived from node.key.pem
# node.key.pem          # secp256r1 private key
# validator-id          # validator ID, used to run as a validator for validating transactions, used in bonds.txt file
#                       # derived from validator.public.pem
# validator-private.pem # ed25519 private key
# validator-public.pem  # ed25519 public key
#
# Use as follows:
# ./node/target/universal/stage/bin/casperlabs-node run \
#   --casper-validator-public-key-path validator-public.pem
#   --casper-validator-private-key-path validator-private.pem
#   --tls-key node.key.pem
#   --tls-certificate node.certificate.pem

# Output directory where to put generated keys
OUTPUT_DIR="$1"

set -v
# Generate validator private key
openssl genpkey -algorithm Ed25519 -out "$OUTPUT_DIR/validator-private.pem"

# Extract validator public key from private
openssl pkey -in "$OUTPUT_DIR/validator-private.pem" -pubout -out "$OUTPUT_DIR/validator-public.pem"

# Extract raw public key from PEM file to serve as a validator ID
openssl pkey -outform DER -pubout -in "$OUTPUT_DIR/validator-private.pem" | tail -c +13 | openssl base64 | tr -d '\n' > "$OUTPUT_DIR/validator-id"

# Generate private TLS key in PEM format
openssl ecparam -name secp256r1 -genkey -noout -out "$OUTPUT_DIR/secp256r1-private.pem"

# Transform key to PKCS#8 format
openssl pkcs8 -topk8 -nocrypt -in "$OUTPUT_DIR/secp256r1-private.pem" -out "$OUTPUT_DIR/node.key.pem"

# Remove old PEM key
rm "$OUTPUT_DIR/secp256r1-private.pem"

# Extract public key, hash it with Keccak-256 and get last 20 bytes to serve as a node ID
# Borrowed from https://ezcook.de/2017/11/30/Generate-Ethereum-keys-and-wallet-address/
NODE_ID=$(cat "$OUTPUT_DIR/node.key.pem" | \
    openssl ec -text -noout | \
    grep pub -A 5 | \
    tail -n +2 | \
    tr -d '\n[:space:]:' | \
    sed 's/^04//' | \
    keccak-256sum -x -l | \
    tr -d ' -' | \
    tail -c 41 | \
    tr -d '\n')
echo "$NODE_ID"> "$OUTPUT_DIR/node-id"

# Generate X.509 certificate
openssl req \
	-new \
	 -x509 \
	 -key "$OUTPUT_DIR/node.key.pem" \
	 -out "$OUTPUT_DIR/node.certificate.pem" \
	 -days 365 \
	 -subj "/C=US/ST=CA/L=San-Diego/O=CasperLabs, LLC/OU=IT Department/CN=$NODE_ID"