import pytest
import logging

from casperlabs_local_net.common import Contract
from casperlabs_local_net.client_parser import parse_show_block
from casperlabs_local_net.client_parser import parse_show_blocks
from casperlabs_local_net.casperlabs_network import OneNodeNetwork
from casperlabs_local_net.casperlabs_accounts import Account
from casperlabs_local_net.wait import wait_for_block_hash_propagated_to_all_nodes
from casperlabs_client import InternalError
from casperlabs_local_net.common import extract_block_hash_from_propose_output
from casperlabs_local_net.casperlabs_accounts import GENESIS_ACCOUNT
from casperlabs_local_net.cli import CLI, DockerCLI

EXECUTION_PAYMENT_AMOUNT = 100 * 10 ** 6

PYTHON_BONDING_ACCT = 294
SCALA_BONDING_ACCT = 295


def add_funded_account_to_network(network: OneNodeNetwork, account_number: int):
    node0 = network.docker_nodes[0]
    prev_number = len(network.docker_nodes)
    account = network.add_new_node_to_network(account=Account(account_number))
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


def bond_to_the_network(
    network: OneNodeNetwork, bond_amount: int, account_number: int, cli_method
):
    account = Account(account_number)
    node0, node1 = network.docker_nodes
    cli = cli_method(node0)
    # fmt: off
    cli("bond",
        "--amount", bond_amount,
        '--private-key', cli.private_key_path(account),
        '--payment-amount', EXECUTION_PAYMENT_AMOUNT)
    # fmt: on
    block_hash = cli("propose")
    return block_hash, account


def unbond_from_network(
    network: OneNodeNetwork, bonding_amount: int, account_number: int, cli_method
):
    node = network.docker_nodes[1]
    account = Account(account_number)
    cli = cli_method(node)
    # fmt: off
    cli("unbond",
        "--amount", bonding_amount,
        "--private-key", cli.private_key_path(account),
        "--payment-amount", EXECUTION_PAYMENT_AMOUNT)
    # fmt: on
    block_hash = cli("propose")
    assert block_hash is not None
    return block_hash, account


def add_account_and_bond_to_network(
    network: OneNodeNetwork, bond_amount: int, account_number: int, cli_client
):
    add_funded_account_to_network(network, account_number)
    return bond_to_the_network(network, bond_amount, account_number, cli_client)


def assert_pre_state_of_network(network: OneNodeNetwork):
    node0 = network.docker_nodes[0]
    blocks = parse_show_blocks(node0.client.show_blocks(1000))
    assert len(blocks) == 1


def check_no_errors_in_deploys(node, block_hash):
    deploy_infos = list(node.p_client.show_deploys(block_hash))
    assert len(deploy_infos) > 0
    for deploy_info in deploy_infos:
        if deploy_info.is_error:
            raise Exception(deploy_info.error_message)


def bonds_by_account_and_stake(
    node, block_hash: str, bonding_amount: int, public_key: str
):
    block = node.d_client.show_block(block_hash)
    block_ds = parse_show_block(block)
    bonds = list(
        filter(
            lambda x: int(x.stake.value) == bonding_amount
            and x.validator_public_key == public_key,
            block_ds.summary.header.state.bonds,
        )
    )
    return bonds


@pytest.mark.parametrize(
    "acct_num,cli_method", ((PYTHON_BONDING_ACCT, CLI), (SCALA_BONDING_ACCT, DockerCLI))
)
def test_bonding_and_unbonding_with_deploys(one_node_network_fn, acct_num, cli_method):
    """
    Feature file: consensus.feature
    Scenario: Bonding a validator node to an existing network.
    """
    # Bond
    logging.info(f"Funding Account {acct_num} and bonding to network.")
    bonding_amount = 1
    assert_pre_state_of_network(one_node_network_fn)
    block_hash, account = add_account_and_bond_to_network(
        one_node_network_fn, bonding_amount, acct_num, cli_method
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
    logging.info(f"Deploy and propose on bonded node.")
    block_hash = node1.d_client.deploy_and_propose(
        from_address=account.public_key_hex,
        session_contract=Contract.HELLO_NAME_DEFINE,
        private_key=account.private_key_docker_path,
        public_key=account.public_key_docker_path,
    )
    wait_for_block_hash_propagated_to_all_nodes(
        one_node_network_fn.docker_nodes, block_hash
    )
    check_no_errors_in_deploys(node1, block_hash)

    # Unbond
    logging.info(f"Unbonding Account {acct_num} from network.")
    block_hash, account = unbond_from_network(
        one_node_network_fn, bonding_amount, acct_num, cli_method
    )
    wait_for_block_hash_propagated_to_all_nodes(
        one_node_network_fn.docker_nodes, block_hash
    )
    check_no_errors_in_deploys(node0, block_hash)

    bonds = bonds_by_account_and_stake(
        node0, block_hash, bonding_amount, account.public_key_hex
    )
    assert len(bonds) == 0

    wait_for_block_hash_propagated_to_all_nodes(
        one_node_network_fn.docker_nodes, block_hash
    )

    # Test deploy/propose unbonded
    logging.info(f"Testing unbonded deploy and propose.")
    node1.d_client.deploy(
        from_address=account.public_key_hex,
        session_contract=Contract.HELLO_NAME_DEFINE,
        private_key=account.private_key_docker_path,
        public_key=account.public_key_docker_path,
    )
    with pytest.raises(InternalError) as excinfo:
        node1.p_client.propose().block_hash.hex()
    assert "InvalidUnslashableBlock" in str(excinfo.value)


@pytest.mark.parametrize(
    "acct_num,cli_method", ((PYTHON_BONDING_ACCT, CLI), (SCALA_BONDING_ACCT, DockerCLI))
)
def test_double_bonding(one_node_network_fn, acct_num, cli_method):
    """
    Feature file: consensus.feature
    Scenario: Bonding a validator node twice to an existing network.
    """

    assert_pre_state_of_network(one_node_network_fn)
    block_hash, account = add_account_and_bond_to_network(
        one_node_network_fn, 1, acct_num, cli_method
    )
    node0, node1 = one_node_network_fn.docker_nodes
    wait_for_block_hash_propagated_to_all_nodes(
        one_node_network_fn.docker_nodes, block_hash
    )
    check_no_errors_in_deploys(node0, block_hash)

    block_hash, account = bond_to_the_network(
        one_node_network_fn, 2, acct_num, cli_method
    )
    wait_for_block_hash_propagated_to_all_nodes(
        one_node_network_fn.docker_nodes, block_hash
    )
    check_no_errors_in_deploys(node1, block_hash)

    bonds = bonds_by_account_and_stake(node1, block_hash, 1 + 2, account.public_key_hex)
    assert len(bonds) == 1


@pytest.mark.parametrize(
    "acct_num,cli_method", ((PYTHON_BONDING_ACCT, CLI), (SCALA_BONDING_ACCT, DockerCLI))
)
def test_partial_amount_unbonding(one_node_network_fn, acct_num, cli_method):
    """
    Feature file: consensus.feature
    Scenario: unbonding a bonded validator node with partial bonding amount from an existing network.
    """
    bonding_amount = 11
    unbond_amount = 4
    remaining_amount = bonding_amount - unbond_amount
    assert_pre_state_of_network(one_node_network_fn)
    block_hash, account = add_account_and_bond_to_network(
        one_node_network_fn, bonding_amount, acct_num, cli_method
    )
    node0, node1 = one_node_network_fn.docker_nodes
    check_no_errors_in_deploys(node0, block_hash)

    bonds = bonds_by_account_and_stake(
        node1, block_hash, bonding_amount, account.public_key_hex
    )
    assert len(bonds) == 1

    block_hash, account = unbond_from_network(
        one_node_network_fn, unbond_amount, acct_num, cli_method
    )
    check_no_errors_in_deploys(node1, block_hash)

    bonds = bonds_by_account_and_stake(
        node1, block_hash, remaining_amount, account.public_key_hex
    )
    assert len(bonds) == 1


@pytest.mark.parametrize(
    "acct_num,cli_method", ((PYTHON_BONDING_ACCT, CLI), (SCALA_BONDING_ACCT, DockerCLI))
)
def test_invalid_bonding_amount(one_node_network_fn, acct_num, cli_method):
    """
    Feature file: consensus.feature
    Scenario: Bonding a validator node to an existing network.
    """
    # 190 is current total staked amount.
    bonding_amount = (190 * 1000) + 1
    block_hash, account = add_account_and_bond_to_network(
        one_node_network_fn, bonding_amount, acct_num, cli_method
    )
    node1 = one_node_network_fn.docker_nodes[1]
    bonds = bonds_by_account_and_stake(
        node1, block_hash, bonding_amount, account.public_key_hex
    )
    assert len(bonds) == 0

    r = node1.d_client.show_deploys(block_hash)[0]
    assert r.is_error is True
    assert r.error_message == "Exit code: 65286"


@pytest.mark.parametrize(
    "acct_num,cli_method", ((PYTHON_BONDING_ACCT, CLI), (SCALA_BONDING_ACCT, DockerCLI))
)
def test_unbonding_without_bonding(one_node_network_fn, acct_num, cli_method):
    """
    Feature file: consensus.feature
    Scenario: unbonding a validator node which was not bonded to an existing network.
    """
    onn = one_node_network_fn
    bonding_amount = 1
    account = Account(acct_num)
    assert_pre_state_of_network(onn)
    add_funded_account_to_network(onn, acct_num)
    assert len(onn.docker_nodes) == 2, "Total number of nodes should be 2."

    node0, node1 = onn.docker_nodes
    cli = cli_method(node0)
    cli(
        "unbond",
        "--amount",
        bonding_amount,
        "--private-key",
        cli.private_key_path(account),
        "--payment-amount",
        EXECUTION_PAYMENT_AMOUNT,
    )
    block_hash = cli("propose")
    r = node0.client.show_deploys(block_hash)[0]
    assert r.is_error is True
    assert r.error_message == "Exit code: 65280"

    block = node1.client.show_block(block_hash)
    block_ds = parse_show_block(block)
    bonds = list(
        filter(
            lambda x: x.validator_public_key == account.public_key_hex,
            block_ds.summary.header.state.bonds,
        )
    )
    assert len(bonds) == 0
