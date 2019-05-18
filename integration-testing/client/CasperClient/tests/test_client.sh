#!/usr/bin/env bash
set -o xtrace
# CasperLabs Client 0.2
#   -h, --host  <arg>   Hostname or IP of node on which gRPC service is running.
#   -p, --port  <arg>   Port used for external gRPC API.
#       --help          Show help message
#   -v, --version       Show version of this program
# 

CL_GRPC_PORT_EXTERNAL=40401 #3477
CLI="$HOME/CasperLabs/client/target/universal/stage/bin/casperlabs-client -- --host localhost --port $CL_GRPC_PORT_EXTERNAL "
CLI="../casper_client/casper_client.py"

RESOURCES_PATH=../../../../integration-testing/resources/

# Subcommand: deploy - Deploy a smart contract source file to Casper on an existing running node. The deploy will be packaged and sent as a block to the network depending on the configuration of the Casper instance.
#   -f, --from  <arg>        Purse address that will be used to pay for the
#                            deployment.
#   -g, --gas-limit  <arg>   The amount of gas to use for the transaction (unused
#                            gas is refunded). Must be positive integer.
#       --gas-price  <arg>   The price of gas for this transaction in units
#                            dust/gas. Must be positive integer.
#   -n, --nonce  <arg>       This allows you to overwrite your own pending
#                            transactions that use the same nonce.
#   -p, --payment  <arg>     Path to the file with payment code
#   -s, --session  <arg>     Path to the file with session code
#   -h, --help               Show help message

CONTRACT=$RESOURCES_PATH/helloname.wasm
PAYMENT=$RESOURCES_PATH/payment.wasm
SESSION=$RESOURCES_PATH/session.wasm

$CLI deploy --from 00000000000000000000 --gas-limit 100000000 --gas-price 1 --session $SESSION --payment $CONTRACT


# Subcommand: propose - Force a node to propose a block based on its accumulated deploys.
#   -h, --help   Show help message

$CLI propose

# Subcommand: show-block - View properties of a block known by Casper on an existing running node.
# Output includes: parent hashes, storage contents of the tuplespace.
#   -h, --help   Show help message
# 
#  trailing arguments:
#   hash (required)   the hash value of the block

$CLI show-block "37052deb42b2c4ccf8799446549c693713eb26ecf8f4f24e3f7db314f93aa2dd"


# Subcommand: show-blocks - View list of blocks in the current Casper view on an existing running node.
#   -d, --depth  <arg>   lists blocks to the given depth in terms of block height
#   -h, --help           Show help message

$CLI show-blocks --depth 10

# Subcommand: vdag - DAG in DOT format
#   -d, --depth  <arg>               depth in terms of block height
#   -o, --out  <arg>                 output image filename, outputs to stdout if
#                                    not specified, must ends with one of the png,
#                                    svg, svg_standalone, xdot, plain, plain_ext,
#                                    ps, ps2, json, json0
#   -s, --show-justification-lines   if justification lines should be shown
#       --stream  <arg>              subscribe to changes, '--out' has to
#                                    specified, valid values are 'single-output',
#                                    'multiple-outputs'
#   -h, --help                       Show help message

$CLI vdag --depth 10

# Subcommand: query-state - Query a value in the global state.
#   -b, --block-hash  <arg>   Hash of the block to query the state of
#   -k, --key  <arg>          Base16 encoding of the base key.
#   -p, --path  <arg>         Path to the value to query. Must be of the form
#                             'key1/key2/.../keyn'
#   -t, --type  <arg>         Type of base key. Must be one of 'hash', 'uref',
#                             'address'
#   -h, --help                Show help message


#blockHash: "6492bb74b047578629b4f4cc5ae9f6282e5a0dfaf17b2c388cdae87e79330b98"
#blockSize: "886"
#blockNumber: 2
#version: 1
#globalStateRootHash: "1cd9b900fb50d052be42bb25d0f4bb47175c3f9b3ba8ea3a5113e191b1c53187"
#timestamp: 1556269941223
#faultTolerance: -1.0
#mainParentHash: "d363e9e6f279deb47783f2ac6cfac87daa778055226effffbe7e932c2189d575"
#parentsHashList: "d363e9e6f279deb47783f2ac6cfac87daa778055226effffbe7e932c2189d575"
#sender: "9dfcf4f851c552df42d656b4bf7e90834061a5dd0a01d64bdea52cbdb76fe2dd"

$CLI query-state --block-hash "6492bb74b047578629b4f4cc5ae9f6282e5a0dfaf17b2c388cdae87e79330b98" --key "9dfcf4f851c552df42d656b4bf7e90834061a5dd0a01d64bdea52cbdb76fe2dd" --path "" --type "hash"

