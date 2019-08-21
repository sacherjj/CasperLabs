import json
from test.cl_node.client_parser import parse_show_blocks
from test.cl_node.docker_node import DockerNode
from test.cl_node.casperlabs_accounts import GENESIS_ACCOUNT
from test.cl_node.casperlabs_accounts import Account
from test.cl_node.common import MAX_PAYMENT_COST, CONV_RATE


def account_state(_block_hash: str, account: str, node0: DockerNode):
    return node0.d_client.query_state(
        block_hash=_block_hash, key_type="address", key=account, path=""
    )


def get_latest_hash(node0):
    blocks = parse_show_blocks(node0.d_client.show_blocks(1000))
    return blocks[0].summary.block_hash


def test_deduct_execution_cost_from_account(payment_node_network):
    network = payment_node_network
    node0: DockerNode = network.docker_nodes[0]
    node0.use_docker_client()
    blocks = parse_show_blocks(node0.d_client.show_blocks(1000))
    genesis_hash = blocks[0].summary.block_hash
    assert len(blocks) == 1  # There should be only one block - the genesis block
    genesis_balance = node0.client.get_balance(
        account_address=GENESIS_ACCOUNT.public_key_hex, block_hash=genesis_hash
    )
    assert genesis_balance == 10 ** 9

    account1 = network.test_account(node0, 10 ** 8)
    account1_block_hash = parse_show_blocks(node0.d_client.show_blocks(1000))[
        0
    ].summary.block_hash
    deploys = node0.client.show_deploys(account1_block_hash)
    deploy_cost = deploys[0].cost
    assert deploy_cost != 0
    account1_balance = node0.client.get_balance(
        account_address=account1.public_key_hex, block_hash=account1_block_hash
    )
    assert account1_balance == 10 ** 8
    genesis_balance_after_transfer = node0.client.get_balance(
        account_address=GENESIS_ACCOUNT.public_key_hex,
        block_hash=parse_show_blocks(node0.d_client.show_blocks(1000))[
            0
        ].summary.block_hash,
    )
    assert (
        genesis_balance
        == genesis_balance_after_transfer + account1_balance + deploy_cost * CONV_RATE
    )


def test_no_min_balance_in_account(payment_node_network_no_min_balance):
    network = payment_node_network_no_min_balance
    node0: DockerNode = network.docker_nodes[0]
    node0.use_docker_client()
    blocks = parse_show_blocks(node0.d_client.show_blocks(1000))
    genesis_hash = blocks[0].summary.block_hash
    assert len(blocks) == 1  # There should be only one block - the genesis block
    genesis_balance = node0.client.get_balance(
        account_address=GENESIS_ACCOUNT.public_key_hex, block_hash=genesis_hash
    )
    assert genesis_balance == 10 ** 3
    block_hash = node0.transfer_to_account(
        1, amount=10 ** 7, is_deploy_error_check=False
    )
    deploy = node0.client.show_deploys(block_hash)[0]
    assert deploy.is_error is True
    assert deploy.error_message == "Insufficient payment"
    cost_of_execution = deploy.cost
    assert cost_of_execution == 0
    genesis_balance_after_transfer = node0.client.get_balance(
        account_address=GENESIS_ACCOUNT.public_key_hex,
        block_hash=parse_show_blocks(node0.d_client.show_blocks(1000))[
            0
        ].summary.block_hash,
    )
    assert genesis_balance == genesis_balance_after_transfer


def test_error_in_payment_contract(payment_node_network):
    network = payment_node_network
    node0: DockerNode = network.docker_nodes[0]
    node0.use_docker_client()
    blocks = parse_show_blocks(node0.d_client.show_blocks(1000))
    genesis_hash = blocks[0].summary.block_hash
    assert len(blocks) == 1  # There should be only one block - the genesis block
    genesis_balance = node0.client.get_balance(
        account_address=GENESIS_ACCOUNT.public_key_hex, block_hash=genesis_hash
    )
    assert genesis_balance == 10 ** 9

    from_account = Account("genesis")
    to_account = Account(1)
    args_json = json.dumps([{"account": to_account.public_key_hex}, {"u32": 10 ** 7}])

    ABI = node0.p_client.abi
    response, deploy_hash_bytes = node0.p_client.deploy(
        from_address=from_account.public_key_hex,
        session_contract="transfer_to_account.wasm",
        payment_contract="err_standard_payment.wasm",
        public_key=from_account.public_key_path,
        private_key=from_account.private_key_path,
        gas_price=1,
        gas_limit=MAX_PAYMENT_COST / CONV_RATE,
        session_args=ABI.args_from_json(args_json),
        payment_args=ABI.args([ABI.u512(10 ** 6)]),
    )
    genesis_balance_after_transfer = node0.client.get_balance(
        account_address=GENESIS_ACCOUNT.public_key_hex,
        block_hash=parse_show_blocks(node0.d_client.show_blocks(1000))[
            0
        ].summary.block_hash,
    )
    assert genesis_balance == genesis_balance_after_transfer


def test_error_in_session_contract(payment_node_network):
    network = payment_node_network
    node0: DockerNode = network.docker_nodes[0]
    node0.use_docker_client()
    blocks = parse_show_blocks(node0.d_client.show_blocks(1000))
    genesis_hash = blocks[0].summary.block_hash
    assert len(blocks) == 1  # There should be only one block - the genesis block
    genesis_balance = node0.client.get_balance(
        account_address=GENESIS_ACCOUNT.public_key_hex, block_hash=genesis_hash
    )
    assert genesis_balance == 10 ** 9
    block_hash = node0.transfer_to_account(
        1,
        session_contract="err_transfer_to_account.wasm",
        amount=10 ** 7,
        is_deploy_error_check=False,
    )
    deploy = node0.client.show_deploys(block_hash)[0]
    assert deploy.is_error is True
    assert deploy.error_message == "Exit code: 1"
    cost_of_execution = deploy.cost
    assert cost_of_execution > 0
    genesis_balance_after_transfer = node0.client.get_balance(
        account_address=GENESIS_ACCOUNT.public_key_hex,
        block_hash=parse_show_blocks(node0.d_client.show_blocks(1000))[
            0
        ].summary.block_hash,
    )
    expected_sum = genesis_balance_after_transfer + cost_of_execution * CONV_RATE
    assert genesis_balance == expected_sum


# The caller has not transferred enough funds to the payment purse
# to completely run the session code.
# The deploy will fail and the caller will not receive a refund.
def test_not_enough_to_run_session(trillion_payment_node_network):
    network = trillion_payment_node_network
    node0: DockerNode = network.docker_nodes[0]
    node0.use_docker_client()
    blocks = parse_show_blocks(node0.d_client.show_blocks(1000))
    genesis_hash = blocks[0].summary.block_hash
    assert len(blocks) == 1  # There should be only one block - the genesis block
    genesis_balance = node0.client.get_balance(
        account_address=GENESIS_ACCOUNT.public_key_hex, block_hash=genesis_hash
    )
    assert genesis_balance == 10 ** 12
    transfer_amount = 10 ** 8
    account1 = network.test_account(node0, transfer_amount)
    account1_starting_balance = node0.client.get_balance(
        account_address=account1.public_key_hex, block_hash=get_latest_hash(node0)
    )
    assert account1_starting_balance == 10 ** 8

    ABI = node0.p_client.abi
    _, _ = node0.p_client.deploy(
        from_address=account1.public_key_hex,
        payment_contract="standard_payment.wasm",
        session_contract="endless_loop.wasm",
        public_key=account1.public_key_path,
        private_key=account1.private_key_path,
        gas_price=1,
        gas_limit=MAX_PAYMENT_COST,
        session_args=None,
        payment_args=ABI.args([ABI.u512(MAX_PAYMENT_COST)]),
    )
    try:
        node0.p_client.propose()
    except Exception as ex:
        print(ex)

    latest_blocks = parse_show_blocks(node0.d_client.show_blocks(1000))
    deploy_hash = latest_blocks[0].summary.block_hash
    deploy = node0.client.show_deploys(deploy_hash)[0]
    assert deploy.cost > 0
    motes = deploy.cost * CONV_RATE
    account1_balance_after_computation = node0.client.get_balance(
        account_address=account1.public_key_hex,
        block_hash=latest_blocks[0].summary.block_hash,
    )
    assert account1_balance_after_computation == account1_starting_balance - motes


# The session code can result in an error.
# The deploy will fail
# and the caller will receive a partial refund for the unspent gas.
def test_refund_after_session_code_error(payment_node_network):
    network = payment_node_network
    node0: DockerNode = network.docker_nodes[0]
    blocks = parse_show_blocks(node0.d_client.show_blocks(1000))
    genesis_init_balance = node0.client.get_balance(
        account_address=GENESIS_ACCOUNT.public_key_hex,
        block_hash=blocks[0].summary.block_hash,
    )

    args_json = json.dumps([{"u32": 10 ** 6}])
    ABI = node0.p_client.abi
    _, deploy_hash = node0.p_client.deploy(
        from_address=GENESIS_ACCOUNT.public_key_hex,
        session_contract="test_args_u512.wasm",
        payment_contract="standard_payment.wasm",
        public_key=GENESIS_ACCOUNT.public_key_path,
        private_key=GENESIS_ACCOUNT.private_key_path,
        gas_price=1,
        gas_limit=MAX_PAYMENT_COST / CONV_RATE,
        session_args=ABI.args([ABI.u512(100)]),
        payment_args=ABI.args_from_json(args_json)
        # 100 is a revert code.
    )
    try:
        node0.p_client.propose()
    except Exception as ex:
        print(ex)

    latest_blocks = parse_show_blocks(node0.d_client.show_blocks(1000))
    deploy_hash = latest_blocks[0].summary.block_hash
    deploy = node0.client.show_deploys(deploy_hash)[0]
    assert deploy.cost == MAX_PAYMENT_COST / CONV_RATE
    motes = deploy.cost * CONV_RATE

    later_balance = node0.client.get_balance(
        account_address=GENESIS_ACCOUNT.public_key_hex, block_hash=deploy_hash
    )

    expected_sum = later_balance + motes
    assert genesis_init_balance == expected_sum


# The caller has not transferred enough funds to the payment purse
# to completely run the payment code.
# The deploy will fail and the caller will not receive a refund.
def test_not_enough_funds_to_run_payment_code(payment_node_network):
    network = payment_node_network
    node0: DockerNode = network.docker_nodes[0]
    node0.use_docker_client()
    blocks = parse_show_blocks(node0.d_client.show_blocks(1000))
    genesis_hash = blocks[0].summary.block_hash
    assert len(blocks) == 1  # There should be only one block - the genesis block
    genesis_balance = node0.client.get_balance(
        account_address=GENESIS_ACCOUNT.public_key_hex, block_hash=genesis_hash
    )
    assert genesis_balance == 10 ** 9
    args_json = json.dumps(
        [{"account": GENESIS_ACCOUNT.public_key_hex}, {"u32": 10 ** 7}]
    )
    ABI = node0.p_client.abi
    _, deploy_hash = node0.p_client.deploy(
        from_address=GENESIS_ACCOUNT.public_key_hex,
        session_contract="transfer_to_account.wasm",
        payment_contract="standard_payment.wasm",
        public_key=GENESIS_ACCOUNT.public_key_path,
        private_key=GENESIS_ACCOUNT.private_key_path,
        gas_price=1,
        gas_limit=MAX_PAYMENT_COST / CONV_RATE,
        session_args=ABI.args_from_json(args_json),
        payment_args=ABI.args([ABI.u512(450)]),
    )

    latest_block_hash = parse_show_blocks(node0.d_client.show_blocks(1000))[
        0
    ].summary.block_hash
    genesis_balance_after_transfer = node0.client.get_balance(
        account_address=GENESIS_ACCOUNT.public_key_hex, block_hash=latest_block_hash
    )
    # assert genesis_balance == genesis_balance_after_transfer + 450 * CONV_RATE ?
    assert genesis_balance == genesis_balance_after_transfer
