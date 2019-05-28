from .cl_node.casperlabsnode import NonZeroExitCodeError

from .cl_node.wait import (
    wait_for_approved_block_received_handler_state,
    wait_for_blocks_count_at_least
)

import pytest


@pytest.mark.parametrize(
    "wasm",
    ["test_helloname.wasm",
     "old_wasm/test_helloname.wasm"]
)
def test_multiple_propose(one_node_network, wasm):
    """
    Feature file: propose.feature
    Scenario: Single node deploy and multiple propose generates an Exception.
    OP-182: First propose should be success, and subsequent propose calls should throw an error/exception.
    """
    node = one_node_network.docker_nodes[0]
    assert 'Success' in node.deploy(session_contract=wasm, payment_contract=wasm)
    assert 'Success' in node.propose()
    number_of_blocks = node.get_blocks_count(100)

    try:
        result = node.propose()
        assert False, "Second propose must not succeed, should throw"
    except NonZeroExitCodeError as e:
        assert e.exit_code == 1, "Second propose should fail"
    wait_for_blocks_count_at_least(node, 1, 1, node.config.command_timeout)

    # Number of blocks after second propose should not change
    assert node.get_blocks_count(100) == number_of_blocks
