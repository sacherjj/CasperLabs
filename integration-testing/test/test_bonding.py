from test.cl_node.common import (
    BONDING_CONTRACT,
    UNBONDING_CONTRACT,
    HELLO_NAME_CONTRACT,
)
from test.cl_node.client_parser import parse_show_block
from test.cl_node.client_parser import parse_show_blocks
from test.cl_node.casperlabs_network import OneNodeNetwork
from test.cl_node.casperlabs_accounts import Account
from test.cl_node.wait import wait_for_block_hash_propagated_to_all_nodes
from casperlabs_client import ABI, InternalError

from typing import List
import logging
import pytest


def bond_to_the_network(network: OneNodeNetwork, contract: str, bond_amount: int):
    network.add_new_node_to_network()
    assert len(network.docker_nodes) == 2, "Total number of nodes should be 2."
    node0, node1 = network.docker_nodes
    block_hash = node1.bond(
        session_contract=contract, payment_contract=contract, amount=bond_amount
    )
    return block_hash


def assert_pre_state_of_network(network: OneNodeNetwork, stakes: List[int]):
    node0 = network.docker_nodes[0]
    blocks = parse_show_blocks(node0.client.show_blocks(1000))
    assert len(blocks) == 1
    genesis_block = blocks[0]
    item = list(
        filter(
            lambda x: x.stake in stakes
            and x.validator_public_key == node0.from_address,
            genesis_block.summary.header.state.bonds,
        )
    )
    assert len(item) == 0


def test_bonding(one_node_network_fn):
    """
    Feature file: consensus.feature
    Scenario: Bonding a validator node to an existing network.
    """
    bonding_amount = 1
    assert_pre_state_of_network(one_node_network_fn, [bonding_amount])
    block_hash = bond_to_the_network(
        one_node_network_fn, BONDING_CONTRACT, bonding_amount
    )
    node0, node1 = one_node_network_fn.docker_nodes
    assert block_hash is not None
    r = node1.client.show_deploys(block_hash)[0]
    assert r.is_error is False
    assert r.error_message == ""

    block1 = node1.client.show_block(block_hash)
    block_ds = parse_show_block(block1)
    public_key = node1.genesis_account.public_key_hex
    item = list(
        filter(
            lambda x: x.stake == bonding_amount
            and x.validator_public_key == public_key,
            block_ds.summary.header.state.bonds,
        )
    )
    assert len(item) == 1


def test_double_bonding(one_node_network_fn):
    """
    Feature file: consensus.feature
    Scenario: Bonding a validator node twice to an existing network.
    """
    bonding_amount = 1
    stakes = [1, 2]
    assert_pre_state_of_network(one_node_network_fn, stakes)
    block_hash = bond_to_the_network(
        one_node_network_fn, BONDING_CONTRACT, bonding_amount
    )
    assert block_hash is not None
    node1 = one_node_network_fn.docker_nodes[1]
    block_hash = node1.bond(
        session_contract=BONDING_CONTRACT,
        payment_contract=BONDING_CONTRACT,
        amount=bonding_amount,
    )
    assert block_hash is not None
    r = node1.client.show_deploys(block_hash)[0]
    assert r.is_error is False
    assert r.error_message == ""

    node1 = one_node_network_fn.docker_nodes[1]
    block1 = node1.client.show_block(block_hash)
    block_ds = parse_show_block(block1)
    public_key = node1.genesis_account.public_key_hex
    item = list(
        filter(
            lambda x: x.stake == bonding_amount + bonding_amount
            and x.validator_public_key == public_key,
            block_ds.summary.header.state.bonds,
        )
    )
    assert len(item) == 1


def test_invalid_bonding(one_node_network_fn):
    """
    Feature file: consensus.feature
    Scenario: Bonding a validator node to an existing network.
    """
    # 190 is current total staked amount.
    bonding_amount = (190 * 1000) + 1
    block_hash = bond_to_the_network(
        one_node_network_fn, BONDING_CONTRACT, bonding_amount
    )
    assert block_hash is not None
    node1 = one_node_network_fn.docker_nodes[1]
    block1 = node1.client.show_block(block_hash)

    r = node1.client.show_deploys(block_hash)[0]
    assert r.is_error is True
    assert r.error_message == "Exit code: 5"

    block_ds = parse_show_block(block1)
    public_key = node1.genesis_account.public_key_hex
    item = list(
        filter(
            lambda x: x.stake == bonding_amount
            and x.validator_public_key == public_key,
            block_ds.summary.header.state.bonds,
        )
    )
    assert len(item) == 0


def test_unbonding(one_node_network_fn):
    """
    Feature file: consensus.feature
    Scenario: unbonding a bonded validator node from an existing network.
    """
    bonding_amount = 1
    assert_pre_state_of_network(one_node_network_fn, [bonding_amount])
    block_hash = bond_to_the_network(
        one_node_network_fn, BONDING_CONTRACT, bonding_amount
    )
    assert block_hash is not None
    node1 = one_node_network_fn.docker_nodes[1]
    public_key = node1.genesis_account.public_key_hex
    block_hash2 = node1.unbond(
        session_contract=UNBONDING_CONTRACT,
        payment_contract=UNBONDING_CONTRACT,
        maybe_amount=None,
    )

    assert block_hash2 is not None
    r = node1.client.show_deploys(block_hash2)[0]
    assert r.is_error is False
    assert r.error_message == ""

    block2 = node1.client.show_block(block_hash2)
    block_ds = parse_show_block(block2)
    item = list(
        filter(
            lambda x: x.stake == bonding_amount
            and x.validator_public_key == public_key,
            block_ds.summary.header.state.bonds,
        )
    )
    assert len(item) == 0


def test_partial_amount_unbonding(one_node_network_fn):
    """
    Feature file: consensus.feature
    Scenario: unbonding a bonded validator node with partial bonding amount from an existing network.
    """
    bonding_amount = 11
    unbond_amount = 4
    assert_pre_state_of_network(
        one_node_network_fn,
        [bonding_amount, unbond_amount, bonding_amount - unbond_amount],
    )
    block_hash = bond_to_the_network(
        one_node_network_fn, BONDING_CONTRACT, bonding_amount
    )
    assert block_hash is not None
    node1 = one_node_network_fn.docker_nodes[1]
    public_key = node1.genesis_account.public_key_hex
    block_hash2 = node1.unbond(
        session_contract=UNBONDING_CONTRACT,
        payment_contract=UNBONDING_CONTRACT,
        maybe_amount=unbond_amount,
    )

    r = node1.client.show_deploys(block_hash2)[0]
    assert r.is_error is False
    assert r.error_message == ""

    assert block_hash2 is not None
    block2 = node1.client.show_block(block_hash2)
    block_ds = parse_show_block(block2)
    item = list(
        filter(
            lambda x: x.stake == bonding_amount - unbond_amount
            and x.validator_public_key == public_key,
            block_ds.summary.header.state.bonds,
        )
    )
    assert len(item) == 1


def test_invalid_unbonding(one_node_network_fn):
    """
    Feature file: consensus.feature
    Scenario: unbonding a bonded validator node from an existing network.
    """
    bonding_amount = 2000
    assert_pre_state_of_network(one_node_network_fn, [bonding_amount])
    block_hash = bond_to_the_network(
        one_node_network_fn, BONDING_CONTRACT, bonding_amount
    )
    assert block_hash is not None
    node1 = one_node_network_fn.docker_nodes[1]
    block_hash2 = node1.unbond(
        session_contract=UNBONDING_CONTRACT,
        payment_contract=UNBONDING_CONTRACT,
        maybe_amount=1985,  # 1985 > (2000+190) * 0.9
    )

    assert block_hash2 is not None
    r = node1.client.show_deploys(block_hash2)[0]
    assert r.is_error is True
    assert r.error_message == "Exit code: 6"
    block2 = node1.client.show_block(block_hash2)
    block_ds = parse_show_block(block2)
    item = list(
        filter(
            lambda x: x.stake == bonding_amount
            and x.validator_public_key == node1.genesis_account.public_key_hex,
            block_ds.summary.header.state.bonds,
        )
    )
    assert len(item) == 1

    block_hash2 = node1.unbond(
        session_contract=UNBONDING_CONTRACT,
        payment_contract=UNBONDING_CONTRACT,
        maybe_amount=None,
    )
    assert block_hash2 is not None
    r = node1.client.show_deploys(block_hash2)[0]
    assert r.is_error is True
    assert r.error_message == "Exit code: 6"
    block2 = node1.client.show_block(block_hash2)
    block_ds = parse_show_block(block2)
    item = list(
        filter(
            lambda x: x.stake == bonding_amount
            and x.validator_public_key == node1.genesis_account.public_key_hex,
            block_ds.summary.header.state.bonds,
        )
    )
    assert len(item) == 1


def test_unbonding_without_bonding(one_node_network_fn):
    """
    Feature file: consensus.feature
    Scenario: unbonding a validator node which was not bonded to an existing network.
    """
    bonding_amount = 1
    assert_pre_state_of_network(one_node_network_fn, [bonding_amount])
    one_node_network_fn.add_new_node_to_network()
    assert (
        len(one_node_network_fn.docker_nodes) == 2
    ), "Total number of nodes should be 2."
    node0, node1 = one_node_network_fn.docker_nodes[:2]
    public_key = node1.genesis_account.public_key_hex
    block_hash = node1.unbond(
        session_contract=UNBONDING_CONTRACT,
        payment_contract=UNBONDING_CONTRACT,
        maybe_amount=None,
    )

    assert block_hash is not None
    r = node1.client.show_deploys(block_hash)[0]
    assert r.is_error is True
    assert r.error_message == "Exit code: 0"

    block2 = node1.client.show_block(block_hash)
    block_ds = parse_show_block(block2)
    item = list(
        filter(
            lambda x: x.validator_public_key == public_key,
            block_ds.summary.header.state.bonds,
        )
    )
    assert len(item) == 0


PAYMENT_CONTRACT = "standard_payment.wasm"


def check_no_errors_in_deploys(node, block_hash):
    deploy_infos = list(node.p_client.show_deploys(block_hash))
    assert len(deploy_infos) > 0
    for deploy_info in deploy_infos:
        assert deploy_info.error_message == ""
        assert deploy_info.is_error is False


def transfer_to_account(
    node,
    from_address: str,
    to_address: str,
    amount: int,
    public_key: str,
    private_key: str,
    gas_price: int = 1,
    gas_limit: int = 5000000,
    session_contract="transfer_to_account.wasm",
    payment_contract="standard_payment.wasm",
    payment_args=ABI.args([ABI.u512(5000000)]),
):

    session_args = ABI.args([ABI.account(bytes.fromhex(to_address)), ABI.u32(amount)])

    _, deploy_hash_bytes = node.p_client.deploy(
        from_address=from_address,
        session_contract=session_contract,
        payment_contract=payment_contract,
        public_key=public_key,
        private_key=private_key,
        gas_price=gas_price,
        gas_limit=gas_limit,
        session_args=session_args,
        payment_args=payment_args,
    )
    deploy_hash_hex = deploy_hash_bytes.hex()
    deploy_hash_hex = deploy_hash_hex
    block_hash = node.p_client.propose().block_hash.hex()
    for deploy_info in node.p_client.show_deploys(block_hash):
        if deploy_info.is_error:
            raise Exception(f"transfer_to_account: {deploy_info.error_message}")
    return block_hash


def test_basic_transfer_to_account(payment_node_network):
    network = payment_node_network
    node = network.docker_nodes[0]

    to_account = Account(1)

    transfer_to_account(
        node,
        node.genesis_account.public_key_hex,
        to_account.public_key_hex,
        1000000,
        public_key=node.genesis_account.public_key_path,
        private_key=node.genesis_account.private_key_path,
    )


def _call_pos_bonding(
    node,
    from_address: str,
    amount: int,
    public_key: str,
    private_key: str,
    gas_price: int = 1,
    gas_limit: int = 5000000,
    session_contract: str = "pos_bonding.wasm",
    payment_contract: str = "standard_payment.wasm",
    payment_args: bytes = ABI.args([ABI.u512(50000000)]),
    method: bytes = b"bond",
    nonce: int = None,
):
    if method == b"bond":
        session_args = ABI.args([ABI.byte_array(method), ABI.u512(amount)])
    elif method == b"unbond":
        session_args = ABI.args([ABI.byte_array(method), ABI.option(ABI.u512(amount))])
    else:
        raise Exception(f"_call_pos_bonding: method {method} not supported")

    node.p_client.deploy(
        from_address=from_address,
        nonce=nonce,
        session_contract=session_contract,
        payment_contract=payment_contract,
        gas_limit=gas_limit,
        gas_price=gas_price,
        public_key=public_key,
        private_key=private_key,
        session_args=session_args,
        payment_args=payment_args,
    )
    block_hash = node.p_client.propose().block_hash.hex()
    for deploy_info in node.p_client.show_deploys(block_hash):
        if deploy_info.is_error:
            raise Exception(f"bond: {deploy_info.error_message}")
    return block_hash


def bond(
    node,
    from_address: str,
    amount: int,
    public_key: str,
    private_key: str,
    gas_price: int = 1,
    gas_limit: int = 5000000,
    session_contract: str = "pos_bonding.wasm",
    payment_contract: str = "standard_payment.wasm",
    payment_args: bytes = ABI.args([ABI.u512(50000000)]),
    nonce=None,
):
    return _call_pos_bonding(
        node,
        from_address,
        amount,
        public_key,
        private_key,
        gas_price,
        gas_limit,
        session_contract,
        payment_contract,
        payment_args,
        b"bond",
        nonce,
    )


def unbond(
    node,
    from_address: str,
    amount: int,
    public_key: str,
    private_key: str,
    gas_price: int = 1,
    gas_limit: int = 5000000,
    session_contract: str = "pos_bonding.wasm",
    payment_contract: str = "standard_payment.wasm",
    payment_args: bytes = ABI.args([ABI.u512(50000000)]),
    nonce=None,
):
    return _call_pos_bonding(
        node,
        from_address,
        amount,
        public_key,
        private_key,
        gas_price,
        gas_limit,
        session_contract,
        payment_contract,
        payment_args,
        b"unbond",
        nonce,
    )


# A new validator bonds
# and crates a block which is accepted by the network,
# then that validator unbonds and creates a block which is not accepted by the network.
def test_unbonding_then_creating_block(payment_node_network):
    network = payment_node_network
    assert len(network.docker_nodes) == 1

    def info(s):
        logging.info(f"{'='*10} | test_unbonding_then_creating_block: {s}")

    network.add_new_node_to_network()
    assert len(network.docker_nodes) == 2

    nodes = network.docker_nodes
    bonding_account = nodes[1].config.node_account

    info(f"CREATE ACCOUNT MATCHING NEW VALIDATOR'S PUBLIC KEY")
    block_hash = nodes[0].transfer_to_account(
        to_account_id=bonding_account.file_id,
        amount=500000000,
        from_account_id="genesis",
        payment_contract="standard_payment.wasm",
        payment_args=ABI.args([ABI.u512(5000000)]),
    )
    info(f"TRANSFER block_hash={block_hash}")
    check_no_errors_in_deploys(nodes[0], block_hash)
    wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)

    # Newly added node is already a validator listed in bonds.txt.
    # Deploy and propose should succeed.
    nodes[1].p_client.deploy(
        from_address=bonding_account.public_key_hex,
        public_key=bonding_account.public_key_path,
        private_key=bonding_account.private_key_path,
        session_contract=HELLO_NAME_CONTRACT,
        payment_contract=PAYMENT_CONTRACT,
        payment_args=ABI.args([ABI.u512(5000000)]),
    )
    first_block_hash_after_bonding = nodes[1].p_client.propose().block_hash.hex()
    check_no_errors_in_deploys(nodes[1], first_block_hash_after_bonding)
    wait_for_block_hash_propagated_to_all_nodes(nodes, first_block_hash_after_bonding)

    # Unbond amount larger than validator's current stake (small 2 digit number).
    info(f"UNBONDING: {bonding_account.public_key_hex}")
    unbonding_block_hash = unbond(
        nodes[0],
        bonding_account.public_key_hex,
        300,
        bonding_account.public_key_path,
        bonding_account.private_key_path,
    )
    check_no_errors_in_deploys(nodes[0], unbonding_block_hash)
    wait_for_block_hash_propagated_to_all_nodes(nodes, unbonding_block_hash)

    # Now deploy and propose is expected to fail, as validator has 0 stakes.
    # Use a different account because otherwise the deploy will get stuck
    # in the deploy buffer and we will not be able to make a new deploy
    # later from the same account on this node.
    genesis_account = nodes[1].genesis_account
    nodes[1].p_client.deploy(
        from_address=genesis_account.public_key_hex,
        public_key=genesis_account.public_key_path,
        private_key=genesis_account.private_key_path,
        session_contract=HELLO_NAME_CONTRACT,
        payment_contract=PAYMENT_CONTRACT,
        payment_args=ABI.args([ABI.u512(5000000)]),
    )
    with pytest.raises(InternalError) as excinfo:
        nodes[1].p_client.propose().block_hash.hex()
    assert "InvalidUnslashableBlock" in str(excinfo.value)

    info(f"BONDING AGAIN: {bonding_account.public_key_hex}")
    bonding_block_hash = bond(
        nodes[0],
        bonding_account.public_key_hex,
        100,
        bonding_account.public_key_path,
        bonding_account.private_key_path,
        nonce=3,
    )

    info(f"BONDING block_hash={bonding_block_hash}")
    check_no_errors_in_deploys(nodes[0], bonding_block_hash)
    wait_for_block_hash_propagated_to_all_nodes(nodes, bonding_block_hash)

    # After bonding again deploy & propose will succeed.
    nodes[1].p_client.deploy(
        from_address=bonding_account.public_key_hex,
        public_key=bonding_account.public_key_path,
        private_key=bonding_account.private_key_path,
        session_contract=HELLO_NAME_CONTRACT,
        payment_contract=PAYMENT_CONTRACT,
        payment_args=ABI.args([ABI.u512(5000000)]),
        nonce=3,
    )
    block_hash = nodes[1].p_client.propose().block_hash.hex()
    check_no_errors_in_deploys(nodes[1], block_hash)
