from test.cl_node.client_parser import parse_show_blocks
from test.cl_node.errors import NonZeroExitCodeError
from test.cl_node.casperlabsnode import extract_block_hash_from_propose_output
from typing import List
import pytest


"""
Feature file: ~/CasperLabs/integration-testing/features/deploy.feature
"""

def deploy_and_propose(node, contract, nonce=None):
    node.client.deploy(session_contract=contract,
                       payment_contract=contract,
                       nonce=nonce)
    return extract_block_hash_from_propose_output(node.client.propose())


def deploy(node, contract, nonce):
    message = node.client.deploy(session_contract=contract, payment_contract=contract, nonce=nonce)
    assert 'Success!' in message
    return message.split()[2]


def propose(node):
    return extract_block_hash_from_propose_output(node.client.propose())


def deploy_hashes(node, block_hash):
    return set(d.deploy.deploy_hash for d in node.client.show_deploys(block_hash))


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

    deploy_hash = deploy(node, contracts[2], 3)

    with pytest.raises(NonZeroExitCodeError):
        node.client.propose()

    deploy_and_propose(node, contracts[1], 2)

    # The deploy with nonce 3 can be proposed now.
    block_hash = propose(node)  
    assert deploy_hash in deploy_hashes(node, block_hash)


@pytest.mark.parametrize("contracts", [['test_helloname.wasm', 'test_helloworld.wasm', 'test_counterdefine.wasm', 'test_countercall.wasm']])
def test_deploy_with_higher_nonce_does_not_include_previous_deploy(node, contracts: List[str]):
    """
    Feature file: deploy.feature

    Scenario: Deploy with higher nonce and created block does not include previously deployed contract.
    """
    # Deploy successfully with nonce 1 => Nonce is 1 for account.
    deploy_hash = deploy(node, contracts[0], 1)
    block_hash = propose(node)
    assert deploy_hash in deploy_hashes(node, block_hash)

    deploy_hash4 = deploy(node, contracts[1], 4)

    with pytest.raises(NonZeroExitCodeError):
        propose(node)

    deploy_hash2 = deploy(node, contracts[2], 2)
    # The deploy with nonce 4 cannot be proposed now. It will be in the deploy buffer but does not include
    # in the new block created now.
    block_hash = propose(node)
    deploys = deploy_hashes(node, block_hash)
    assert deploy_hash4 not in deploys
    assert deploy_hash2 in deploys

    deploy_hash3 = deploy(node, contracts[3], 3)
    block_hash = propose(node)
    assert deploy_hash3 in deploy_hashes(node, block_hash)

    block_hash = propose(node)
    assert deploy_hash4 in deploy_hashes(node, block_hash)
