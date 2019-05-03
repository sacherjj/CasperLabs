
from .cl_node.wait import (
    wait_for_blocks_count_at_least
)


def test_propose(one_node_network):
    node = one_node_network.clnodes[0].node
    result = node.deploy()
    assert 'Success!' in str(result)
    result = node.propose()
    assert 'Success!' in str(result)
    wait_for_blocks_count_at_least(node, 2, 4, node.config.command_timeout)
