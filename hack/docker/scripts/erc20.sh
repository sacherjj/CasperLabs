set -e
set -u

CL_CLIENT=${CL_CLIENT:-"casperlabs-client"}
CL_HOST=${CL_HOST:-"deploy.casperlabs.io"}
ERC20_USE_COLORS=${ERC20_USE_COLORS:-true}
ERC20_WASM=${ERC20_WASM:-"../../../execution-engine/target/wasm32-unknown-unknown/release/erc20_smart_contract.wasm"}
TOKEN_NAME=${TOKEN_NAME:-"test-token"}
AUTO_PROPOSE=${AUTO_PROPOSE:-false}
QUERY_DEPLOY_STATUS=${QUERY_DEPLOY_STATUS:-false}
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

if [ "${ERC20_USE_COLORS}" = true ] ; then
    RED="\e[91m"
    YELLOW="\e[93m"
    GREEN="\e[32m"
    RESET="\e[0m"
else
    RED=""
    YELLOW=""
    GREEN=""
    RESET=""
fi  

command_help () {
    echo "CasperLabs ERC20 Bash client."
    echo ""
    echo "Usage: $(basename "$0") <command> [options]"
    echo ""
    echo "Commands:"
    echo "  deploy <private-key-path> <initial-amount>"
    echo "                           Deploy ERC20 smart contract with the <initial-amount> of tokens."
    echo "                           The account that deployed the token is refered later as <contract-host-account>"
    echo "                           as the smart contract lives under this account."
    echo ""
    echo "  balance <contract-host-address> <owner-address>" 
    echo "                           Check the balance of <owner-address> account."
    echo ""
    echo "  transfer <private-key-path> <contract-host-address> <recipient-address> <amount>"
    echo "                           Transfer <amount> of tokens to <recipient-address> from your account."
    echo ""
    echo "  approve <private-key-path> <contract-host-address> <spender-address> <amount>"
    echo "                           Approve <spender-address> account to transfer <amount>"
    echo "                           tokens from your account."
    echo ""
    echo "  allowance <contract-host-address> <owner-address> <spender-address>"
    echo "                           Check the amount of tokens <spender-address> can transfer from"
    echo "                           <owner-address> account."
    echo ""
    echo "  transferFrom <private-key-path> <contract-host-address> <owner-address> <recipient-address> <amount>"
    echo "                           Transfer <amount> tokens from <owner-address> account"
    echo "                           to <recipient-address> account."
    echo ""
    echo "  buy <private-key-path> <contract-host-address> <amount>"
    echo "                           Exchange CLX for tokens."
    echo ""
    echo "  sell <private-key-path> <contract-host-address> <amount>"
    echo "                           Exchange tokens for CLX."
    echo ""
    echo "  help                     Print this message."
    echo ""
    echo "Exported Bash variables."
    echo "  CL_CLIENT=${CL_CLIENT}"
    echo "  CL_HOST=${CL_HOST}"
    echo "  ERC20_USE_COLORS=${ERC20_USE_COLORS}"
    echo "  ERC20_WASM=${ERC20_WASM}"
    echo "  TOKEN_NAME=${TOKEN_NAME}"
    echo "  AUTO_PROPOSE=${AUTO_PROPOSE}"
    echo "  QUERY_DEPLOY_STATUS=${QUERY_DEPLOY_STATUS}"
    echo ""
}

log() {
    echo -e "  ${GREEN}[x]${RESET} $1"
}

err_log() {
    echo -e "  ${RED}[x] $1${RESET}"
}

handle_deploy_response () {
    RESPONSE=${1}
    DEPLOY_HASH=$(echo ${RESPONSE} | awk '{print $3}')
    log "Deployed with hash ${DEPLOY_HASH}"
    if [ "${AUTO_PROPOSE}" = true ] ; then
        log "Proposing block."
        ${CL_CLIENT} --host ${CL_HOST} propose
    fi    
    if [ "${QUERY_DEPLOY_STATUS}" = true ] ; then
        log "Checking deploy status."
        ${CL_CLIENT} --host ${CL_HOST} show-deploy ${DEPLOY_HASH}
    fi    
}

last_block () {
    RESPONSE=$(${CL_CLIENT} --host ${CL_HOST} show-blocks --depth 1)
    BLOCK_HASH=$(echo ${RESPONSE} | awk -F "block_hash: \"" '{print $2}' | awk -F "\" header" '{print $1}')
    echo ${BLOCK_HASH}
}

contract_hash_by_name() {
    DEPLOYER=${1}
    CONTRACT_NAME=${2}
    BLOCK_HASH=${3}
    RESPONSE=$(${CL_CLIENT} --host ${CL_HOST} query-state \
        --block-hash ${BLOCK_HASH} \
        --type address \
        --key ${DEPLOYER} \
        --path ${CONTRACT_NAME})
    HASH=$(echo ${RESPONSE} | awk -F "\"" '{print $2}')
    echo $HASH
}

command_deploy () {
    SENDER_PRIVATE_KEY=${1}
    TOTAL_SUPPLY=${2}
    ARGS="[\
        {\"name\": \"method\", \"value\": {\"string_value\": \"deploy\"}}, \
        {\"name\": \"token_name\", \"value\": {\"string_value\": \"${TOKEN_NAME}\"}}, \
        {\"name\": \"initial_balance\", \"value\": {\"big_int\": {\"value\": \"${TOTAL_SUPPLY}\", \"bit_width\": 512}}} \
    ]"
    RESPONSE=$(${CL_CLIENT} --host ${CL_HOST} deploy \
        --private-key ${SENDER_PRIVATE_KEY} \
        --payment-amount 10000000 \
        --session ${ERC20_WASM} \
        --session-args "${ARGS}"
    )
    handle_deploy_response "${RESPONSE}"
}

command_balance () {
    HOST_PUBLIC_HEX=${1}
    ACCOUNT_PUBLIC_HEX=${2}
    BLOCK_HASH=$(last_block)
    log "Checking balance at block ${BLOCK_HASH}."
    TOKEN_HASH=$(contract_hash_by_name ${HOST_PUBLIC_HEX} ${TOKEN_NAME} ${BLOCK_HASH})
    set +e
    RESPONSE=$(${CL_CLIENT} --host ${CL_HOST} query-state \
        --block-hash ${BLOCK_HASH} \
        --type local \
        --key "${TOKEN_HASH}:${BALANCE_KEY_SIZE_HEX}${BALANCE_BYTE}${ACCOUNT_PUBLIC_HEX}" 2>&1)
    set -e
    BALANCE=$(echo ${RESPONSE} | awk -F "\"" '{print $2}')
    if [ "${BALANCE}" = "Value not found: " ] ; then
        BALANCE=0
    fi
    log "Account ${YELLOW}${ACCOUNT_PUBLIC_HEX}${RESET} has ${YELLOW}${BALANCE}${RESET} tokens."
}

command_transfer () {
    SENDER_PRIVATE_KEY=${1}
    HOST_PUBLIC_HEX=${2}
    RECIPIENT_PUBLIC_KEY=${3}
    AMOUNT=${4}
    BLOCK_HASH=$(last_block)
    TOKEN_HASH=$(contract_hash_by_name ${HOST_PUBLIC_HEX} ${TOKEN_NAME} ${BLOCK_HASH})
    PROXY_HASH=$(contract_hash_by_name ${HOST_PUBLIC_HEX} ${PROXY_NAME} ${BLOCK_HASH})
    ARGS="[\
        {\"name\": \"erc20\", \"value\": {\"bytes_value\": \"${TOKEN_HASH}\"}}, \
        {\"name\": \"method\", \"value\": {\"string_value\": \"transfer\"}}, \
        {\"name\": \"recipient\", \"value\": {\"bytes_value\": \"${RECIPIENT_PUBLIC_KEY}\"}}, \
        {\"name\": \"amount\", \"value\": {\"big_int\": {\"value\": \"${AMOUNT}\", \"bit_width\": 512}}} \
    ]"
    RESPONSE=$(${CL_CLIENT} --host ${CL_HOST} deploy \
        --private-key ${SENDER_PRIVATE_KEY} \
        --payment-amount 10000000 \
        --session-hash ${PROXY_HASH} \
        --session-args "${ARGS}"
    )
    handle_deploy_response "${RESPONSE}"
}

command_approve () {
    SENDER_PRIVATE_KEY=${1}
    HOST_PUBLIC_HEX=${2}
    SPENDER_PUBLIC_KEY=${3}
    AMOUNT=${4}
    BLOCK_HASH=$(last_block)
    TOKEN_HASH=$(contract_hash_by_name ${HOST_PUBLIC_HEX} ${TOKEN_NAME} ${BLOCK_HASH})
    PROXY_HASH=$(contract_hash_by_name ${HOST_PUBLIC_HEX} ${PROXY_NAME} ${BLOCK_HASH})
    ARGS="[\
        {\"name\": \"erc20\", \"value\": {\"bytes_value\": \"${TOKEN_HASH}\"}}, \
        {\"name\": \"method\", \"value\": {\"string_value\": \"approve\"}}, \
        {\"name\": \"spender\", \"value\": {\"bytes_value\": \"${SPENDER_PUBLIC_KEY}\"}}, \
        {\"name\": \"amount\", \"value\": {\"big_int\": {\"value\": \"${AMOUNT}\", \"bit_width\": 512}}} \
    ]"
    RESPONSE=$(${CL_CLIENT} --host ${CL_HOST} deploy \
        --private-key ${SENDER_PRIVATE_KEY} \
        --payment-amount 10000000 \
        --session-hash ${PROXY_HASH} \
        --session-args "${ARGS}"
    )
    handle_deploy_response "${RESPONSE}"
}

command_allowance () {
    HOST_PUBLIC_HEX=${1}
    OWNER_PUBLIC_KEY=${2}
    SPENDER_PUBLIC_KEY=${3}
    BLOCK_HASH=$(last_block)
    log "Checking allowance at block ${BLOCK_HASH}."
    TOKEN_HASH=$(contract_hash_by_name ${HOST_PUBLIC_HEX} ${TOKEN_NAME} ${BLOCK_HASH})
    set +e
    RESPONSE=$(${CL_CLIENT} --host ${CL_HOST} query-state \
        --block-hash ${BLOCK_HASH} \
        --type local \
        --key "${TOKEN_HASH}:${ALLOWANCE_KEY_SIZE_HEX}${OWNER_PUBLIC_KEY}${SPENDER_PUBLIC_KEY}" 2>&1)
    set -e
    ALLOWANCE=$(echo $RESPONSE | awk -F "\"" '{print $2}')
    if [ "${ALLOWANCE}" = "Value not found: " ] ; then
        ALLOWANCE=0
    fi
    log "Account ${YELLOW}${OWNER_PUBLIC_KEY}${RESET} allows ${YELLOW}${SPENDER_PUBLIC_KEY}${RESET} to spend ${YELLOW}${ALLOWANCE}${RESET} tokens."
}

command_transfer_from () {
    SENDER_PRIVATE_KEY=${1}
    HOST_PUBLIC_HEX=${2}
    OWNER_PUBLIC_KEY=${3}
    RECIPIENT_PUBLIC_KEY=${4}
    AMOUNT=$5
    BLOCK_HASH=$(last_block)
    TOKEN_HASH=$(contract_hash_by_name ${HOST_PUBLIC_HEX} ${TOKEN_NAME} ${BLOCK_HASH})
    PROXY_HASH=$(contract_hash_by_name ${HOST_PUBLIC_HEX} ${PROXY_NAME} ${BLOCK_HASH})
    ARGS="[\
        {\"name\": \"erc20\", \"value\": {\"bytes_value\": \"${TOKEN_HASH}\"}}, \
        {\"name\": \"method\", \"value\": {\"string_value\": \"transfer_from\"}}, \
        {\"name\": \"owner\", \"value\": {\"bytes_value\": \"${OWNER_PUBLIC_KEY}\"}}, \
        {\"name\": \"recipient\", \"value\": {\"bytes_value\": \"${RECIPIENT_PUBLIC_KEY}\"}}, \
        {\"name\": \"amount\", \"value\": {\"big_int\": {\"value\": \"${AMOUNT}\", \"bit_width\": 512}}} \
    ]"
    RESPONSE=$(${CL_CLIENT} --host ${CL_HOST} deploy \
        --private-key ${SENDER_PRIVATE_KEY} \
        --payment-amount 10000000 \
        --session-hash ${PROXY_HASH} \
        --session-args "${ARGS}"
    )
    handle_deploy_response "${RESPONSE}"
}

command_buy () {
    SENDER_PRIVATE_KEY=${1}
    HOST_PUBLIC_HEX=${2}
    AMOUNT=$3
    BLOCK_HASH=$(last_block)
    TOKEN_HASH=$(contract_hash_by_name ${HOST_PUBLIC_HEX} ${TOKEN_NAME} ${BLOCK_HASH})
    PROXY_HASH=$(contract_hash_by_name ${HOST_PUBLIC_HEX} ${PROXY_NAME} ${BLOCK_HASH})
    ARGS="[\
        {\"name\": \"erc20\", \"value\": {\"bytes_value\": \"${TOKEN_HASH}\"}}, \
        {\"name\": \"method\", \"value\": {\"string_value\": \"buy_proxy\"}}, \
        {\"name\": \"amount\", \"value\": {\"big_int\": {\"value\": \"${AMOUNT}\", \"bit_width\": 512}}} \
    ]"
    RESPONSE=$(${CL_CLIENT} --host ${CL_HOST} deploy \
        --private-key ${SENDER_PRIVATE_KEY} \
        --payment-amount 10000000 \
        --session-hash ${PROXY_HASH} \
        --session-args "${ARGS}"
    )
    handle_deploy_response "${RESPONSE}"
}

command_sell () {
    SENDER_PRIVATE_KEY=${1}
    HOST_PUBLIC_HEX=${2}
    AMOUNT=$3
    BLOCK_HASH=$(last_block)
    TOKEN_HASH=$(contract_hash_by_name ${HOST_PUBLIC_HEX} ${TOKEN_NAME} ${BLOCK_HASH})
    PROXY_HASH=$(contract_hash_by_name ${HOST_PUBLIC_HEX} ${PROXY_NAME} ${BLOCK_HASH})
    ARGS="[\
        {\"name\": \"erc20\", \"value\": {\"bytes_value\": \"${TOKEN_HASH}\"}}, \
        {\"name\": \"method\", \"value\": {\"string_value\": \"sell_proxy\"}}, \
        {\"name\": \"amount\", \"value\": {\"big_int\": {\"value\": \"${AMOUNT}\", \"bit_width\": 512}}} \
    ]"
    RESPONSE=$(${CL_CLIENT} --host ${CL_HOST} deploy \
        --private-key ${SENDER_PRIVATE_KEY} \
        --payment-amount 10000000 \
        --session-hash ${PROXY_HASH} \
        --session-args "${ARGS}"
    )
    handle_deploy_response "${RESPONSE}"
}

COMMAND=${1:-"help"}
case $COMMAND in
    deploy)
        command_deploy $2 $3
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
    help|--help|-h)
        command_help
        ;;
    *)
        err_log "Unknown command: ${COMMAND}."
        log "Call '${YELLOW}$(basename "$0") help${RESET}' for available commands."
        exit 2
esac
