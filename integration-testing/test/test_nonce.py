from test.cl_node.client_parser import parse_show_blocks
from test.cl_node.errors import NonZeroExitCodeError
from test.cl_node.casperlabsnode import extract_block_hash_from_propose_output
from typing import List
import pytest


"""
Feature file: ~/CasperLabs/integration-testing/features/deploy.feature
"""


def deploy_and_propose(node, contract, nonce=None):
    node.client.deploy(session_contract=contract, payment_contract=contract, nonce=nonce)
    return extract_block_hash_from_propose_output(node.client.propose())


@pytest.mark.parametrize("contract", ['test_helloname.wasm',])
def test_deploy_without_nonce(node, contract: str):
    """
    Feature file: deploy.feature
    Scenario: Deploy without nonce
    """
    with pytest.raises(NonZeroExitCodeError):
        deploy_and_propose(node, contract, '')


@pytest.mark.parametrize("contracts", [['test_helloname.wasm', 'test_helloworld.wasm', 'test_counterdefine.wasm']])
def test_deploy_with_lower_nonce(node, contracts: List[str]):
    """
    Feature file: deploy.feature
    Scenario: Deploy with lower nonce
    """
    for contract in contracts:
        deploy_and_propose(node, contract)

    with pytest.raises(NonZeroExitCodeError):
        deploy_and_propose(node, contract, 2)


@pytest.mark.parametrize("contracts", [['test_helloname.wasm', 'test_helloworld.wasm', 'test_counterdefine.wasm']])
def test_deploy_with_higher_nonce(node, contracts: List[str]):
    """
    Feature file: deploy.feature

    Scenario: Deploy with higher nonce
    """
    # Deploy successfully with nonce 1 => Nonce is 1 for account.
    deploy_and_propose(node, contracts[0], 1)

    node.client.deploy(session_contract = contracts[2], payment_contract = contracts[2], nonce = 3)

    with pytest.raises(NonZeroExitCodeError):
        node.client.propose()

    deploy_and_propose(node, contracts[1], 2)

    # The deploy with nonce 3 can be proposed now.
    node.client.propose()  

    blocks = parse_show_blocks(node.client.show_blocks(100))

    # Deploy counts of all blocks except the genesis block.
    deploy_counts = [b.summary.header.deploy_count for b in blocks][:-1]

    assert sum(deploy_counts) == len(contracts)


@pytest.mark.parametrize("contracts", [['test_helloname.wasm', 'test_helloworld.wasm', 'test_counterdefine.wasm', 'test_countercall.wasm']])
def test_deploy_with_higher_nonce_does_not_include_previous_deploy(node, contracts: List[str]):
    """
    Feature file: deploy.feature

    Scenario: Deploy with higher nonce and created block does not include previously deployed contract.
    """
    # Deploy successfully with nonce 1 => Nonce is 1 for account.
    deploy_and_propose(node, contracts[0], 1)

    node.client.deploy(session_contract=contracts[1], payment_contract=contracts[1], nonce=4)

    with pytest.raises(NonZeroExitCodeError):
        node.client.propose()

    node.client.deploy(session_contract=contracts[2], payment_contract=contracts[2], nonce=2)
    # The deploy with nonce 4 cannot be proposed now. It will be in the deploy buffer but does not include
    # in the new block created now.
    node.client.propose()
    blocks = parse_show_blocks(node.client.show_blocks(100))

    # Deploy counts of all blocks except the genesis block.
    deploy_counts = [b.summary.header.deploy_count for b in blocks][:-1]

    assert sum(deploy_counts) == 2

    deploy_and_propose(node, contracts[3], 3)
    node.client.propose()
    blocks = parse_show_blocks(node.client.show_blocks(100))

    # Deploy counts of all blocks except the genesis block.
    deploy_counts = [b.summary.header.deploy_count for b in blocks][:-1]

    assert sum(deploy_counts) == len(contracts)
