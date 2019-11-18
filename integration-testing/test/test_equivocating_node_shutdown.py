import logging
import pytest
from casperlabs_local_net.casperlabs_accounts import GENESIS_ACCOUNT
from casperlabs_local_net.common import Contract
from casperlabs_local_net.wait import wait_for_block_hash_propagated_to_all_nodes


def test_equivocating_node_shutdown(two_node_network):
    network = two_node_network
    nodes = network.docker_nodes
    account = GENESIS_ACCOUNT

    deploy_options = dict(
        from_address=account.public_key_hex,
        public_key=account.public_key_path,
        private_key=account.private_key_path,
        session_contract=Contract.HELLO_NAME_DEFINE,
    )

    block_hash = nodes[0].p_client.deploy_and_propose(**deploy_options)
    wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)

    # Clear state of node-0 so when we restart it
    # it doesn't remember the block it created before.
    cmd = "rm /root/.casperlabs/sqlite.db"
    rc, output = nodes[0].exec_run(cmd)
    logging.info(f"============================ {cmd} => {rc}")

    network.stop_cl_node(0)
    network.stop_cl_node(1)

    network.start_cl_node(0)
    # After node-0 restarted it should only have the genesis block.
    assert len(list(nodes[0].p_client.show_blocks(1000))) == 1

    # Create the equivocating block.
    block_hash = nodes[0].p_client.deploy_and_propose(**deploy_options)

    network.start_cl_node(1)
    wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)

    assert len(list(nodes[1].p_client.show_blocks(1000))) == 3

    block_hash = nodes[1].p_client.deploy_and_propose(**deploy_options)
    with pytest.raises(Exception):
        wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)

    # Check node-0 is offline. If it terminated the docker container it was running in stopped as well.
    with pytest.raises(Exception) as excinfo:
        nodes[0].d_client.show_blocks(1)
    assert "Unable to resolve host node-0" in str(excinfo.value.output)

    assert "Node has detected it's own equivocation with block" in nodes[0].logs()
