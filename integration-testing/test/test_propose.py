import os

import pytest

from rnode_testing.rnode import started_standalone_bootstrap_node
from rnode_testing.rnode import node_payment_code
from rnode_testing.rnode import node_session_code
from rnode_testing.wait import (
    wait_for_approved_block_received_handler_state,
    wait_for_blocks_count_at_least
)

from typing import TYPE_CHECKING


def test_propose(started_standalone_bootstrap_node):
    wait_for_approved_block_received_handler_state(started_standalone_bootstrap_node, started_standalone_bootstrap_node.timeout)
    started_standalone_bootstrap_node.deploy(node_session_code, node_payment_code)
    started_standalone_bootstrap_node.propose()
    wait_for_blocks_count_at_least(started_standalone_bootstrap_node, 1, 1, started_standalone_bootstrap_node.timeout)
