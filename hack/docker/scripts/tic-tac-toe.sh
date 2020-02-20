#!/usr/bin/env bash

set -e
set -u

BASE_PATH=${BASE_PATH:-"../../.."}
CL_CLIENT=${CL_CLIENT:-"${BASE_PATH}/client/target/universal/stage/bin/casperlabs-client"}
CL_WASM=${CL_WASM:-"${BASE_PATH}/execution-engine/target/wasm32-unknown-unknown/release/tic_tac_toe_smart_contract.wasm"}
CL_HOST=${CL_HOST:-"localhost"}

AUTO_PROPOSE=${AUTO_PROPOSE:-false}
GAME_CONTRACT_NAME=${GAME_CONTRACT_NAME:-"tic_tac_toe"}
PROXY_CONTRACT_NAME=${PROXY_CONTRACT_NAME:-"tic_tac_toe_proxy"}

RED="\e[91m"
YELLOW="\e[93m"
GREEN="\e[32m"
RESET="\e[0m"

command_help () {
    echo "CasperLabs Tic Tac Toe bash client."
    echo ""
    echo "Usage: $(basename "$0") <command> [options]"
    echo ""
    echo "Commands:"
    echo "  deploy <private-key-path>  Deploy the game smart contract."
    echo "  game-status <host-address> <player-address>"
    echo "                             Check the status of a game of <player-address>."
    echo "  start <private-key-path> <host-address> <player-x-address> <player-o-address>"
    echo "                             Start the game for <player-x-address> vs <player-o-address>."
    echo "  move <private-key-path> <host-address> <row> <column>"
    echo "                             Make a move on the board at (row, column)." 
    echo "                             <row> and <column> can be 0, 1 or 2." 
    echo "  concede <private-key-path> <host-address>"
    echo "                             Resigne from the game."
    echo "  help                       Print this message."
    echo ""
}

log() {
    echo -e "  ${GREEN}[x]${RESET} $1"
}

propose_block_if_enabled () {
    if [ "${AUTO_PROPOSE}" = true ] ; then
        log "Proposing block."
        $CL_CLIENT --host $CL_HOST propose
    fi    
}

last_block () {
    RESPONSE=$($CL_CLIENT --host $CL_HOST show-blocks --depth 1)
    BLOCK_HASH=$(echo $RESPONSE | awk -F "block_hash: \"" '{print $2}' | awk -F "\" header" '{print $1}')
    echo $BLOCK_HASH
}

contract_hash_by_name() {
    HOST_ACCOUNT=$1
    CONTRACT_NAME=$2
    BLOCK_HASH=$3
    RESPONSE=$($CL_CLIENT --host $CL_HOST query-state \
        --block-hash ${BLOCK_HASH} \
        --type address \
        --key ${HOST_ACCOUNT} \
        --path ${CONTRACT_NAME})
    HASH=$(echo $RESPONSE | awk -F "\"" '{print $2}')
    echo $HASH
}

player_status() {
    GAME_CONTRACT_HASH=$1
    BLOCK_HASH=$2
    PLAYER_ACCOUNT=$3
    RESPONSE=$($CL_CLIENT --host $CL_HOST query-state \
        --block-hash ${BLOCK_HASH} \
        --type hash \
        --key ${GAME_CONTRACT_HASH} \
        --path "PublicKey(${PLAYER_ACCOUNT})")
    HASH=$(echo $RESPONSE | awk -F "\"" '{print $2}')
    echo $HASH    
}

game_status() {
    GAME_CONTRACT_HASH=$1
    BLOCK_HASH=$2
    PLAYER_ACCOUNT=$3
    OPPONENT_ACCOUNT=$4
    if [[ "$PLAYER_ACCOUNT" > "$OPPONENT_ACCOUNT" ]]; then
        ACCOUNT_1=${PLAYER_ACCOUNT}
        ACCOUNT_2=${OPPONENT_ACCOUNT}
    else
        ACCOUNT_1=${OPPONENT_ACCOUNT}
        ACCOUNT_2=${PLAYER_ACCOUNT}
    fi
    RESPONSE=$($CL_CLIENT --host $CL_HOST query-state \
        --block-hash ${BLOCK_HASH} \
        --type hash \
        --key ${GAME_CONTRACT_HASH} \
        --path "Game PublicKey(${ACCOUNT_1}) vs PublicKey(${ACCOUNT_2})")
    HASH=$(echo $RESPONSE | awk -F "\"" '{print $2}')
    echo $HASH    
}


command_deploy () {
    SENDER_PRIVATE_KEY="$1"
    RESPONSE=$($CL_CLIENT --host $CL_HOST deploy \
        --private-key $SENDER_PRIVATE_KEY \
        --payment-amount 10000000 \
        --session $CL_WASM
    )
    DEPLOY_HASH=$(echo $RESPONSE | awk '{print $3}')
    log "Game contract deployed with hash $DEPLOY_HASH"
    propose_block_if_enabled
    log "Checking deploy status."
    $CL_CLIENT --host $CL_HOST show-deploy $DEPLOY_HASH
}

command_game_status () {
    HOST_PUBLIC_KEY="$1"
    PLAYER_PUBLIC_KEY="$2"
    BLOCK_HASH=$(last_block)
    GAME_HASH=$(contract_hash_by_name ${HOST_PUBLIC_KEY} ${GAME_CONTRACT_NAME} ${BLOCK_HASH})
    PROXY_HASH=$(contract_hash_by_name ${HOST_PUBLIC_KEY} ${PROXY_CONTRACT_NAME} ${BLOCK_HASH})
    PLAYER_STATUS=$(player_status ${GAME_HASH} ${BLOCK_HASH} ${PLAYER_PUBLIC_KEY})
    OPPONENT_PUBLIC_KEY=$(echo ${PLAYER_STATUS} | awk -F "(" '{print $2}' | awk -F ")" '{print $1}')
    OPPONENT_STATUS=$(player_status ${GAME_HASH} ${BLOCK_HASH} ${OPPONENT_PUBLIC_KEY})
    GAME_STATUS=$(game_status ${GAME_HASH} ${BLOCK_HASH} ${PLAYER_PUBLIC_KEY} ${OPPONENT_PUBLIC_KEY})
    S=$(echo ${GAME_STATUS} | sed "s/_/ /g")
    log "Game host: ${HOST_PUBLIC_KEY}."
    log "Game contract hash: ${GAME_HASH}."
    log "Proxy contract hash: ${PROXY_HASH}."
    log "Player account (YOU): ${PLAYER_PUBLIC_KEY}."
    log "Player status: \"${PLAYER_STATUS}\"."
    log "Opponent account: ${OPPONENT_PUBLIC_KEY}."
    log "Opponent status: \"${OPPONENT_STATUS}\"."
    log "Board:"
    log "${S:0:1}|${S:1:1}|${S:2:1}"
    log "-+-+-"
    log "${S:3:1}|${S:4:1}|${S:5:1}"
    log "-+-+-"
    log "${S:6:1}|${S:7:1}|${S:8:1}"
}

command_start () {
    DEPLOYER_PRIVATE_KEY="$1"
    HOST_PUBLIC_KEY="$2"
    PLAYER_X_PUBLIC_KEY="$3"
    PLAYER_O_PUBLIC_KEY="$4"
    BLOCK_HASH=$(last_block)
    GAME_HASH=$(contract_hash_by_name ${HOST_PUBLIC_KEY} ${GAME_CONTRACT_NAME} ${BLOCK_HASH})
    PROXY_HASH=$(contract_hash_by_name ${HOST_PUBLIC_KEY} ${PROXY_CONTRACT_NAME} ${BLOCK_HASH})
    ARGS="[\
        {\"name\": \"game\", \"value\": {\"bytes_value\": \"${GAME_HASH}\"}}, \
        {\"name\": \"method\", \"value\": {\"string_value\": \"start\"}}, \
        {\"name\": \"player_x\", \"value\": {\"bytes_value\": \"${PLAYER_X_PUBLIC_KEY}\"}}, \
        {\"name\": \"player_o\", \"value\": {\"bytes_value\": \"${PLAYER_O_PUBLIC_KEY}\"}} \
    ]"
    RESPONSE=$($CL_CLIENT --host $CL_HOST deploy \
        --private-key $DEPLOYER_PRIVATE_KEY \
        --payment-amount 10000000 \
        --session-hash ${PROXY_HASH} \
        --session-args "${ARGS}"
    )
    DEPLOY_HASH=$(echo $RESPONSE | awk '{print $3}')
    log "Game start deployed with hash $DEPLOY_HASH"
    propose_block_if_enabled
    log "Checking deploy status."
    $CL_CLIENT --host $CL_HOST show-deploy $DEPLOY_HASH
}

command_move () {
    DEPLOYER_PRIVATE_KEY="$1"
    HOST_PUBLIC_KEY="$2"
    X="$3"
    Y="$4"
    re='^[0-2]$'
    if ! [[ $X =~ $re ]] ; then
        log "Invalid row: ${RED}${X}${RESET}. Exit!"
        exit 2
    fi
    if ! [[ $Y =~ $re ]] ; then
        log "Invalid column: ${RED}${Y}${RESET}. Exit!"
        exit 2
    fi
    BLOCK_HASH=$(last_block)
    GAME_HASH=$(contract_hash_by_name ${HOST_PUBLIC_KEY} ${GAME_CONTRACT_NAME} ${BLOCK_HASH})
    PROXY_HASH=$(contract_hash_by_name ${HOST_PUBLIC_KEY} ${PROXY_CONTRACT_NAME} ${BLOCK_HASH})
    ARGS="[\
        {\"name\": \"game\", \"value\": {\"bytes_value\": \"${GAME_HASH}\"}}, \
        {\"name\": \"method\", \"value\": {\"string_value\": \"move\"}}, \
        {\"name\": \"row\", \"value\": {\"int_value\": \"${X}\"}}, \
        {\"name\": \"column\", \"value\": {\"int_value\": \"${Y}\"}} \
    ]"
    RESPONSE=$($CL_CLIENT --host $CL_HOST deploy \
        --private-key $DEPLOYER_PRIVATE_KEY \
        --payment-amount 10000000 \
        --session-hash ${PROXY_HASH} \
        --session-args "${ARGS}"
    )
    DEPLOY_HASH=$(echo $RESPONSE | awk '{print $3}')
    log "Move deployed with hash $DEPLOY_HASH"
    propose_block_if_enabled
    log "Checking deploy status."
    $CL_CLIENT --host $CL_HOST show-deploy $DEPLOY_HASH
}

command_concede () {
    DEPLOYER_PRIVATE_KEY="$1"
    HOST_PUBLIC_KEY="$2"
    BLOCK_HASH=$(last_block)
    GAME_HASH=$(contract_hash_by_name ${HOST_PUBLIC_KEY} ${GAME_CONTRACT_NAME} ${BLOCK_HASH})
    PROXY_HASH=$(contract_hash_by_name ${HOST_PUBLIC_KEY} ${PROXY_CONTRACT_NAME} ${BLOCK_HASH})
    ARGS="[\
        {\"name\": \"game\", \"value\": {\"bytes_value\": \"${GAME_HASH}\"}}, \
        {\"name\": \"method\", \"value\": {\"string_value\": \"concede\"}} \
    ]"
    RESPONSE=$($CL_CLIENT --host $CL_HOST deploy \
        --private-key $DEPLOYER_PRIVATE_KEY \
        --payment-amount 10000000 \
        --session-hash ${PROXY_HASH} \
        --session-args "${ARGS}"
    )
    DEPLOY_HASH=$(echo $RESPONSE | awk '{print $3}')
    log "Concede deployed with hash $DEPLOY_HASH"
    propose_block_if_enabled
    log "Checking deploy status."
    $CL_CLIENT --host $CL_HOST show-deploy $DEPLOY_HASH
}

COMMAND=${1:-"help"}
case $COMMAND in
    deploy)
        command_deploy $2
        ;;
    game-status)
        command_game_status $2 $3
        ;;
    start)
        command_start $2 $3 $4 $5
        ;;
    move)
        command_move $2 $3 $4 $5
        ;;
    concede)
        command_concede $2 $3
        ;;
    help)
        command_help
        ;;
    *)
        log "Unknown command: ${RED}${COMMAND}${RESET}."
        log "Call '${YELLOW}$(basename "$0") help${RESET}' for available commands."
        exit 2
esac
