set -e
set -u

BASE_PATH=${BASE_PATH:-"../../.."}
CLIENT=${CLIENT:-"${BASE_PATH}/client/target/universal/stage/bin/casperlabs-client"}
HOST=${HOST:-"localhost"}
TOTAL_SUPPLY=${TOTAL_SUPPLY:-20000}
AUTO_PROPOSE=${AUTO_PROPOSE:-true}
ERC20_WASM=${ERC20_WASM:-"${BASE_PATH}/execution-engine/target/wasm32-unknown-unknown/release/erc20_smart_contract.wasm"}
TOKEN_NAME=${TOKEN_NAME:-"test-token"}

ACCOUNT_PRIVATE_FILE="account-private.pem"
ACCOUNT_ID_HEX_FILE="account-id-hex"
PROXY_NAME="erc20_proxy"

# At the beginning of a serialized version of Rust's Vec<u8>, first 4 bytes represent the size of the vector.
#
# Balances are 33 bytes arrays where: 
#   - the first byte is "01";
#   - the rest is 32 bytes of the account's public key.
#
# Allowances are 64 bytes arrays where: 
#   - the first 32 bytes are token owner's public key; 
#   - the second 32 bytes are token spender's public key.
#
# Decimal version of "21 00 00 00" is 33.
# Decimal version of "40 00 00 00" is 64.
BALANCE_KEY_SIZE_HEX="21000000"
ALLOWANCE_KEY_SIZE_HEX="40000000"
BALANCE_BYTE="01"

RED="\e[91m"
YELLOW="\e[93m"
GREEN="\e[32m"
RESET="\e[0m"

command_help () {
    echo "CasperLabs ERC20 bash client."
    echo ""
    echo "Usage: $(basename "$0") <command> [options]"
    echo ""
    echo "Commands:"
    echo "  deploy <deployer>"
    echo "                           Deploy ERC20 smart contract."
    echo "  balance <deployer> <owner>" 
    echo "                           Check the balance of <owner> account."
    echo "  transfer <deployer> <owner> <recipient> <amount>"
    echo "                           Transfer tokens from <owner> to <recipient>."
    echo "  approve <deployer> <owner> <spender> <amount>"
    echo "                           Approve <spender> account to transfer <amount>"
    echo "                           tokens from <owner> account."
    echo "  allowance <deployer> <owner> <spender>"
    echo "                           Check the allowance of <spender> account for"
    echo "                           <owner> account."
    echo "  transferFrom <deployer> <spender> <owner> <recipient> <amount>"
    echo "                           Transfer <amount> tokens from <owner> account"
    echo "                           to <recipient> account using <spender> account."
    echo "  buy <deployer> <owner> <amount>"
    echo "                           Exchange CLX for tokens."
    echo "  sell <deployer> <owner> <amount>"
    echo "                           Exchange tokens for CLX."
    echo "  help"
    echo "                           Print this message."
    echo ""
    echo "<deployer>, <recipient>, <owner>, <spender> are directories,"
    echo "and should contain '${ACCOUNT_PRIVATE_FILE}' and '${ACCOUNT_ID_HEX_FILE}' files."
}

log() {
    echo -e "  ${GREEN}[x]${RESET} $1"
}

propose_block_if_enabled () {
    if [ "${AUTO_PROPOSE}" = true ] ; then
        log "Proposing block."
        $CLIENT --host $HOST propose
    fi    
}

last_block () {
    RESPONSE=$($CLIENT --host $HOST show-blocks)
    BLOCK_HASH=$(echo $RESPONSE | awk -F "block_hash: \"" '{print $2}' | awk -F "\" header" '{print $1}')
    echo $BLOCK_HASH
}

contract_hash_by_name() {
    DEPLOYER=$1
    CONTRACT_NAME=$2
    BLOCK_HASH=$3
    RESPONSE=$($CLIENT --host $HOST query-state \
        --block-hash ${BLOCK_HASH} \
        --type address \
        --key ${DEPLOYER} \
        --path ${CONTRACT_NAME})
    HASH=$(echo $RESPONSE | awk -F "\"" '{print $2}')
    echo $HASH
}

command_deploy () {
    SENDER_PRIVATE_KEY="$1/$ACCOUNT_PRIVATE_FILE"
    ARGS="[\
        {\"name\": \"method\", \"value\": {\"string_value\": \"deploy\"}}, \
        {\"name\": \"token_name\", \"value\": {\"string_value\": \"$TOKEN_NAME\"}}, \
        {\"name\": \"initial_balance\", \"value\": {\"big_int\": {\"value\": \"$TOTAL_SUPPLY\", \"bit_width\": 512}}} \
    ]"
    RESPONSE=$($CLIENT --host $HOST deploy \
        --private-key $SENDER_PRIVATE_KEY \
        --payment-amount 10000000 \
        --session $ERC20_WASM \
        --session-args "$ARGS"
    )
    DEPLOY_HASH=$(echo $RESPONSE | awk '{print $3}')
    log "ERC20 deployed with hash $DEPLOY_HASH"
    propose_block_if_enabled
    log "Checking deploy status."
    $CLIENT --host $HOST show-deploy $DEPLOY_HASH
}

command_balance () {
    DEPLOYER_PUBLIC_HEX=$(cat ${1}/${ACCOUNT_ID_HEX_FILE})
    ACCOUNT_PUBLIC_HEX=$(cat ${2}/${ACCOUNT_ID_HEX_FILE})
    BLOCK_HASH=$(last_block)
    log "Checking balance at block ${BLOCK_HASH}."
    TOKEN_HASH=$(contract_hash_by_name ${DEPLOYER_PUBLIC_HEX} ${TOKEN_NAME} ${BLOCK_HASH})
    RESPONSE=$($CLIENT query-state --block-hash $BLOCK_HASH \
        --type local \
        --key "${TOKEN_HASH}:${BALANCE_KEY_SIZE_HEX}${BALANCE_BYTE}${ACCOUNT_PUBLIC_HEX}")
    BALANCE=$(echo $RESPONSE | awk -F "\"" '{print $2}')
    log "Account ${YELLOW}${ACCOUNT_PUBLIC_HEX}${RESET} has ${YELLOW}${BALANCE}${RESET} tokens."
}

command_transfer () {
    DEPLOYER_PUBLIC_HEX=$(cat ${1}/${ACCOUNT_ID_HEX_FILE})
    SENDER_PRIVATE_KEY="${2}/${ACCOUNT_PRIVATE_FILE}"
    RECIPIENT_PUBLIC_KEY=$(cat ${3}/${ACCOUNT_ID_HEX_FILE})
    AMOUNT=$4
    BLOCK_HASH=$(last_block)
    TOKEN_HASH=$(contract_hash_by_name ${DEPLOYER_PUBLIC_HEX} ${TOKEN_NAME} ${BLOCK_HASH})
    PROXY_HASH=$(contract_hash_by_name ${DEPLOYER_PUBLIC_HEX} ${PROXY_NAME} ${BLOCK_HASH})
    ARGS="[\
        {\"name\": \"erc20\", \"value\": {\"bytes_value\": \"${TOKEN_HASH}\"}}, \
        {\"name\": \"method\", \"value\": {\"string_value\": \"transfer\"}}, \
        {\"name\": \"recipient\", \"value\": {\"bytes_value\": \"${RECIPIENT_PUBLIC_KEY}\"}}, \
        {\"name\": \"amount\", \"value\": {\"big_int\": {\"value\": \"${AMOUNT}\", \"bit_width\": 512}}} \
    ]"
    RESPONSE=$($CLIENT --host ${HOST} deploy \
        --private-key ${SENDER_PRIVATE_KEY} \
        --payment-amount 10000000 \
        --session-hash ${PROXY_HASH} \
        --session-args "${ARGS}"
    )
    DEPLOY_HASH=$(echo $RESPONSE | awk '{print $3}')
    log "ERC20 transfer deployed with hash ${DEPLOY_HASH}"
    propose_block_if_enabled
    log "Checking transfer status."
    $CLIENT --host $HOST show-deploy $DEPLOY_HASH   
}

command_approve () {
    DEPLOYER_PUBLIC_HEX=$(cat ${1}/${ACCOUNT_ID_HEX_FILE})
    OWNER_PRIVATE_KEY="${2}/${ACCOUNT_PRIVATE_FILE}"
    SPENDER_PUBLIC_KEY=$(cat ${3}/${ACCOUNT_ID_HEX_FILE})
    AMOUNT=$4
    BLOCK_HASH=$(last_block)
    TOKEN_HASH=$(contract_hash_by_name ${DEPLOYER_PUBLIC_HEX} ${TOKEN_NAME} ${BLOCK_HASH})
    PROXY_HASH=$(contract_hash_by_name ${DEPLOYER_PUBLIC_HEX} ${PROXY_NAME} ${BLOCK_HASH})
    ARGS="[\
        {\"name\": \"erc20\", \"value\": {\"bytes_value\": \"${TOKEN_HASH}\"}}, \
        {\"name\": \"method\", \"value\": {\"string_value\": \"approve\"}}, \
        {\"name\": \"recipient\", \"value\": {\"bytes_value\": \"${SPENDER_PUBLIC_KEY}\"}}, \
        {\"name\": \"amount\", \"value\": {\"big_int\": {\"value\": \"${AMOUNT}\", \"bit_width\": 512}}} \
    ]"
    RESPONSE=$($CLIENT --host ${HOST} deploy \
        --private-key ${OWNER_PRIVATE_KEY} \
        --payment-amount 10000000 \
        --session-hash ${PROXY_HASH} \
        --session-args "${ARGS}"
    )
    DEPLOY_HASH=$(echo $RESPONSE | awk '{print $3}')
    log "ERC20 approval deployed with hash ${DEPLOY_HASH}"
    propose_block_if_enabled
    log "Checking approval status."
    $CLIENT --host $HOST show-deploy $DEPLOY_HASH   
}

command_allowance () {
    DEPLOYER_PUBLIC_HEX=$(cat ${1}/${ACCOUNT_ID_HEX_FILE})
    OWNER_PUBLIC_KEY=$(cat ${2}/${ACCOUNT_ID_HEX_FILE})
    SPENDER_PUBLIC_KEY=$(cat ${3}/${ACCOUNT_ID_HEX_FILE})
    BLOCK_HASH=$(last_block)
    log "Checking allowance at block ${BLOCK_HASH}."
    TOKEN_HASH=$(contract_hash_by_name ${DEPLOYER_PUBLIC_HEX} ${TOKEN_NAME} ${BLOCK_HASH})
    RESPONSE=$($CLIENT query-state --block-hash $BLOCK_HASH \
        --type local \
        --key "${TOKEN_HASH}:${ALLOWANCE_KEY_SIZE_HEX}${OWNER_PUBLIC_KEY}${SPENDER_PUBLIC_KEY}")
    ALLOWANCE=$(echo $RESPONSE | awk -F "\"" '{print $2}')
    log "Account ${YELLOW}${OWNER_PUBLIC_KEY}${RESET} allows ${YELLOW}${SPENDER_PUBLIC_KEY}${RESET} to spend ${YELLOW}${ALLOWANCE}${RESET} tokens."
}

command_transfer_from () {
    DEPLOYER_PUBLIC_HEX=$(cat ${1}/${ACCOUNT_ID_HEX_FILE})
    SENDER_PRIVATE_KEY="${2}/${ACCOUNT_PRIVATE_FILE}"
    OWNER_PUBLIC_KEY=$(cat ${3}/${ACCOUNT_ID_HEX_FILE})
    RECIPIENT_PUBLIC_KEY=$(cat ${4}/${ACCOUNT_ID_HEX_FILE})
    AMOUNT=$5
    BLOCK_HASH=$(last_block)
    TOKEN_HASH=$(contract_hash_by_name ${DEPLOYER_PUBLIC_HEX} ${TOKEN_NAME} ${BLOCK_HASH})
    PROXY_HASH=$(contract_hash_by_name ${DEPLOYER_PUBLIC_HEX} ${PROXY_NAME} ${BLOCK_HASH})
    ARGS="[\
        {\"name\": \"erc20\", \"value\": {\"bytes_value\": \"${TOKEN_HASH}\"}}, \
        {\"name\": \"method\", \"value\": {\"string_value\": \"transfer_from\"}}, \
        {\"name\": \"owner\", \"value\": {\"bytes_value\": \"${OWNER_PUBLIC_KEY}\"}}, \
        {\"name\": \"recipient\", \"value\": {\"bytes_value\": \"${RECIPIENT_PUBLIC_KEY}\"}}, \
        {\"name\": \"amount\", \"value\": {\"big_int\": {\"value\": \"${AMOUNT}\", \"bit_width\": 512}}} \
    ]"
    RESPONSE=$($CLIENT --host ${HOST} deploy \
        --private-key ${SENDER_PRIVATE_KEY} \
        --payment-amount 10000000 \
        --session-hash ${PROXY_HASH} \
        --session-args "${ARGS}"
    )
    DEPLOY_HASH=$(echo $RESPONSE | awk '{print $3}')
    log "ERC20 transferFrom deployed with hash ${DEPLOY_HASH}."
    propose_block_if_enabled
    log "Checking transferFrom status."
    $CLIENT --host $HOST show-deploy $DEPLOY_HASH   
}

command_buy () {
    DEPLOYER_PUBLIC_HEX=$(cat ${1}/${ACCOUNT_ID_HEX_FILE})
    BUYER_PRIVATE_KEY="${2}/${ACCOUNT_PRIVATE_FILE}"
    AMOUNT=$3
    BLOCK_HASH=$(last_block)
    TOKEN_HASH=$(contract_hash_by_name ${DEPLOYER_PUBLIC_HEX} ${TOKEN_NAME} ${BLOCK_HASH})
    PROXY_HASH=$(contract_hash_by_name ${DEPLOYER_PUBLIC_HEX} ${PROXY_NAME} ${BLOCK_HASH})
    ARGS="[\
        {\"name\": \"erc20\", \"value\": {\"bytes_value\": \"${TOKEN_HASH}\"}}, \
        {\"name\": \"method\", \"value\": {\"string_value\": \"buy_proxy\"}}, \
        {\"name\": \"amount\", \"value\": {\"big_int\": {\"value\": \"${AMOUNT}\", \"bit_width\": 512}}} \
    ]"
    RESPONSE=$($CLIENT --host ${HOST} deploy \
        --private-key ${BUYER_PRIVATE_KEY} \
        --payment-amount 10000000 \
        --session-hash ${PROXY_HASH} \
        --session-args "${ARGS}"
    )
    DEPLOY_HASH=$(echo $RESPONSE | awk '{print $3}')
    log "ERC20 buy deployed with hash ${DEPLOY_HASH}"
    propose_block_if_enabled
    log "Checking buy status."
    $CLIENT --host $HOST show-deploy $DEPLOY_HASH   
}

command_sell () {
    DEPLOYER_PUBLIC_HEX=$(cat ${1}/${ACCOUNT_ID_HEX_FILE})
    SELLER_PRIVATE_KEY="${2}/${ACCOUNT_PRIVATE_FILE}"
    AMOUNT=$3
    BLOCK_HASH=$(last_block)
    TOKEN_HASH=$(contract_hash_by_name ${DEPLOYER_PUBLIC_HEX} ${TOKEN_NAME} ${BLOCK_HASH})
    PROXY_HASH=$(contract_hash_by_name ${DEPLOYER_PUBLIC_HEX} ${PROXY_NAME} ${BLOCK_HASH})
    ARGS="[\
        {\"name\": \"erc20\", \"value\": {\"bytes_value\": \"${TOKEN_HASH}\"}}, \
        {\"name\": \"method\", \"value\": {\"string_value\": \"sell_proxy\"}}, \
        {\"name\": \"amount\", \"value\": {\"big_int\": {\"value\": \"${AMOUNT}\", \"bit_width\": 512}}} \
    ]"
    RESPONSE=$($CLIENT --host ${HOST} deploy \
        --private-key ${SELLER_PRIVATE_KEY} \
        --payment-amount 10000000 \
        --session-hash ${PROXY_HASH} \
        --session-args "${ARGS}"
    )
    DEPLOY_HASH=$(echo $RESPONSE | awk '{print $3}')
    log "ERC20 sell deployed with hash ${DEPLOY_HASH}"
    propose_block_if_enabled
    log "Checking sell status."
    $CLIENT --host $HOST show-deploy $DEPLOY_HASH   
}

COMMAND=${1:-"help"}
case $COMMAND in
    deploy)
        command_deploy $2
        ;;
    balance)
        command_balance $2 $3
        ;;
    transfer)
        command_transfer $2 $3 $4 $5
        ;;
    approve)
        command_approve $2 $3 $4 $5
        ;;
    allowance)
        command_allowance $2 $3 $4
        ;;
    transferFrom)
        command_transfer_from $2 $3 $4 $5 $6
        ;;
    buy)
        command_buy $2 $3 $4
        ;;
    sell)
        command_sell $2 $3 $4
        ;;
    help)
        command_help
        ;;
    *)
        log "Unknown command: ${RED}${COMMAND}${RESET}."
        log "Call '${YELLOW}$(basename "$0") help${RESET}' for available commands."
        exit 2
esac
