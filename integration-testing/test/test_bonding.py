import pytest

from casperlabs_local_net.common import Contract
from casperlabs_local_net.client_parser import parse_show_block
from casperlabs_local_net.client_parser import parse_show_blocks
from casperlabs_local_net.casperlabs_network import OneNodeNetwork
from casperlabs_local_net.casperlabs_accounts import Account
from casperlabs_local_net.wait import wait_for_block_hash_propagated_to_all_nodes
from casperlabs_client import InternalError
from casperlabs_local_net.common import extract_block_hash_from_propose_output
from casperlabs_local_net.casperlabs_accounts import GENESIS_ACCOUNT


BONDING_ACCT = 295


def add_funded_account_to_network(network: OneNodeNetwork, account_number: int):
    node0 = network.docker_nodes[0]
    prev_number = len(network.docker_nodes)
    account = network.add_new_node_to_network(Account(account_number))
    assert (
        len(network.docker_nodes) == prev_number + 1
    ), f"Total number of nodes should be {prev_number + 1}."
    response = node0.d_client.transfer(
        amount=1000000000,
        private_key=GENESIS_ACCOUNT.private_key_docker_path,
        target_account=account.public_key,
    )
    assert "Success!" in response
    response = node0.d_client.propose()
    block_hash = extract_block_hash_from_propose_output(response)
    assert block_hash is not None
    r = node0.client.show_deploys(block_hash)[0]
    assert r.is_error is False, f"Transfer Failed: {r.error_message}"
    assert r.error_message == ""


def bond_to_the_network(network: OneNodeNetwork, bond_amount: int, account_number: int):
    # Using account that will not exist in bonds.txt from high number
    account = Account(account_number)
    node0, node1 = network.docker_nodes
    response = node0.d_client.bond(
        amount=bond_amount, private_key=account.private_key_docker_path
    )
    assert "Success!" in response
    response = node0.d_client.propose()
    block_hash = extract_block_hash_from_propose_output(response)
    assert block_hash is not None
    return block_hash, account


def unbond_from_network(
    network: OneNodeNetwork, bonding_amount: int, account_number: int
):
    node = network.docker_nodes[1]
    account = Account(account_number)
    r = node.d_client.unbond(bonding_amount, account.private_key_docker_path)
    assert "Success!" in r
    r = node.d_client.propose()
    block_hash = extract_block_hash_from_propose_output(r)
    assert block_hash is not None
    return block_hash, account


def add_account_and_bond_to_network(
    network: OneNodeNetwork, bond_amount: int, account_number: int
):
    add_funded_account_to_network(network, account_number)
    return bond_to_the_network(network, bond_amount, account_number)


def assert_pre_state_of_network(network: OneNodeNetwork):
    node0 = network.docker_nodes[0]
    blocks = parse_show_blocks(node0.client.show_blocks(1000))
    assert len(blocks) == 1


def check_no_errors_in_deploys(node, block_hash):
    deploy_infos = list(node.p_client.show_deploys(block_hash))
    assert len(deploy_infos) > 0
    for deploy_info in deploy_infos:
        assert deploy_info.is_error is False, deploy_info.error_message
        assert deploy_info.error_message == ""


def bonds_by_account_and_stake(
    node, block_hash: str, bonding_amount: int, public_key: str
):
    block = node.d_client.show_block(block_hash)
    block_ds = parse_show_block(block)
    bonds = list(
        filter(
            lambda x: x.stake == bonding_amount
            and x.validator_public_key == public_key,
            block_ds.summary.header.state.bonds,
        )
    )
    return bonds


def test_bonding_and_unbonding_with_deploys(one_node_network_fn):
    """
    Feature file: consensus.feature
    Scenario: Bonding a validator node to an existing network.
    """
    # Bond
    bonding_amount = 1
    assert_pre_state_of_network(one_node_network_fn)
    block_hash, account = add_account_and_bond_to_network(
        one_node_network_fn, bonding_amount, BONDING_ACCT
    )

    node0, node1 = one_node_network_fn.docker_nodes
    check_no_errors_in_deploys(node0, block_hash)

    bonds = bonds_by_account_and_stake(
        node1, block_hash, bonding_amount, account.public_key_hex
    )
    assert len(bonds) == 1

    wait_for_block_hash_propagated_to_all_nodes(
        one_node_network_fn.docker_nodes, block_hash
    )

    # Test deploy/propose bonded
    block_hash = node1.d_client.deploy_and_propose(
        from_address=account.public_key_hex,
        session_contract=Contract.HELLONAME,
        private_key=account.private_key_docker_path,
        public_key=account.public_key_docker_path,
    )
    check_no_errors_in_deploys(node1, block_hash)

    # Unbond
    block_hash, account = unbond_from_network(
        one_node_network_fn, bonding_amount, BONDING_ACCT
    )
    check_no_errors_in_deploys(node0, block_hash)

    bonds = bonds_by_account_and_stake(
        node0, block_hash, bonding_amount, account.public_key_hex
    )
    assert len(bonds) == 0

    # Test deploy/propose unbonded
    node1.d_client.deploy(
        from_address=account.public_key_hex,
        session_contract=Contract.HELLONAME,
        private_key=account.private_key_docker_path,
        public_key=account.public_key_docker_path,
    )
    with pytest.raises(InternalError) as excinfo:
        node1.p_client.propose().block_hash.hex()
    assert "InvalidUnslashableBlock" in str(excinfo.value)


def test_double_bonding(one_node_network_fn):
    """
    Feature file: consensus.feature
    Scenario: Bonding a validator node twice to an existing network.
    """

    assert_pre_state_of_network(one_node_network_fn)
    block_hash, account = add_account_and_bond_to_network(
        one_node_network_fn, 1, BONDING_ACCT
    )
    node0, node1 = one_node_network_fn.docker_nodes
    check_no_errors_in_deploys(node0, block_hash)

    block_hash, account = bond_to_the_network(one_node_network_fn, 2, BONDING_ACCT)
    check_no_errors_in_deploys(node1, block_hash)

    bonds = bonds_by_account_and_stake(node1, block_hash, 1 + 2, account.public_key_hex)
    assert len(bonds) == 1


def test_partial_amount_unbonding(one_node_network_fn):
    """
    Feature file: consensus.feature
    Scenario: unbonding a bonded validator node with partial bonding amount from an existing network.
    """
    bonding_amount = 11
    unbond_amount = 4
    remaining_amount = bonding_amount - unbond_amount
    assert_pre_state_of_network(one_node_network_fn)
    block_hash, account = add_account_and_bond_to_network(
        one_node_network_fn, bonding_amount, BONDING_ACCT
    )
    node0, node1 = one_node_network_fn.docker_nodes
    check_no_errors_in_deploys(node0, block_hash)

    bonds = bonds_by_account_and_stake(
        node1, block_hash, bonding_amount, account.public_key_hex
    )
    assert len(bonds) == 1

    block_hash, account = unbond_from_network(
        one_node_network_fn, unbond_amount, BONDING_ACCT
    )
    check_no_errors_in_deploys(node1, block_hash)

    bonds = bonds_by_account_and_stake(
        node1, block_hash, remaining_amount, account.public_key_hex
    )
    assert len(bonds) == 1


def test_invalid_bonding_amount(one_node_network_fn):
    """
    Feature file: consensus.feature
    Scenario: Bonding a validator node to an existing network.
    """
    # 190 is current total staked amount.
    bonding_amount = (190 * 1000) + 1
    block_hash, account = add_account_and_bond_to_network(
        one_node_network_fn, bonding_amount, BONDING_ACCT
    )
    node1 = one_node_network_fn.docker_nodes[1]
    bonds = bonds_by_account_and_stake(
        node1, block_hash, bonding_amount, account.public_key_hex
    )
    assert len(bonds) == 0

    r = node1.d_client.show_deploys(block_hash)[0]
    assert r.is_error is True
    assert r.error_message == "Exit code: 5"


def test_unbonding_without_bonding(one_node_network_fn):
    """
    Feature file: consensus.feature
    Scenario: unbonding a validator node which was not bonded to an existing network.
    """
    bonding_amount = 1
    account = Account(BONDING_ACCT)
    assert_pre_state_of_network(one_node_network_fn)
    add_funded_account_to_network(one_node_network_fn, BONDING_ACCT)
    assert (
        len(one_node_network_fn.docker_nodes) == 2
    ), "Total number of nodes should be 2."

    node0, node1 = one_node_network_fn.docker_nodes
    r = node0.d_client.unbond(bonding_amount, account.private_key_docker_path)
    assert "Success!" in r
    r = node0.d_client.propose()
    block_hash = extract_block_hash_from_propose_output(r)
    assert block_hash is not None
    # block_hash, account = unbond_from_network(one_node_network_fn, bonding_amount, BONDING_ACCT)

    r = node0.client.show_deploys(block_hash)[0]
    assert r.is_error is True
    assert r.error_message == "Exit code: 0"

    block = node1.client.show_block(block_hash)
    block_ds = parse_show_block(block)
    bonds = list(
        filter(
            lambda x: x.validator_public_key == account.public_key_hex,
            block_ds.summary.header.state.bonds,
        )
    )
    assert len(bonds) == 0
