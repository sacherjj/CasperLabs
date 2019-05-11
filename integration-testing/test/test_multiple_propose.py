from .cl_node.casperlabsnode import NonZeroExitCodeError

from .cl_node.wait import (
    wait_for_approved_block_received_handler_state,
    wait_for_blocks_count_at_least
)

import pytest


@pytest.mark.parametrize(
    "wasm",
    ["helloname.wasm",
     "old_wasm/helloname.wasm"]
)
def test_multiple_propose(started_standalone_bootstrap_node, wasm):
    """
    OP-182: First propose should be success, and subsequent propose calls should throw an error/exception.
    """
    wait_for_approved_block_received_handler_state(started_standalone_bootstrap_node, started_standalone_bootstrap_node.timeout)
    started_standalone_bootstrap_node.deploy(session=wasm,
                                             payment=wasm)
    result = started_standalone_bootstrap_node.propose()
    number_of_blocks = started_standalone_bootstrap_node.get_blocks_count(100)

    try:
        result = started_standalone_bootstrap_node.propose()
        assert False, "Second propose must not succeed, should throw"
    except NonZeroExitCodeError as e:
        assert e.exit_code == 1, "Second propose should fail"
    wait_for_blocks_count_at_least(started_standalone_bootstrap_node, 1, 1, started_standalone_bootstrap_node.timeout)

    # Number of blocks after second ppropose should not chaange
    assert started_standalone_bootstrap_node.get_blocks_count(100) == number_of_blocks
