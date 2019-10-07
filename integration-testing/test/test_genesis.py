from pytest import raises
from casperlabs_local_net.wait import wait_for_block_hash_propagated_to_all_nodes


def test(two_node_with_different_accounts_csv_network):
    """
    Check that two nodes started with different accounts.csv
    will create different genesis blocks
    and will not exchange blocks, effectively not becoming part of one network.
    """
    nodes = two_node_with_different_accounts_csv_network.docker_nodes

    account = nodes[0].genesis_account

    nodes[0].client.deploy(
        session_contract="test_helloname.wasm",
        from_address=account.public_key_hex,
        public_key=account.public_key_path,
        private_key=account.private_key_path,
    )
    block_hash = nodes[0].client.propose()
    with raises(Exception) as excinfo:
        wait_for_block_hash_propagated_to_all_nodes(nodes, block_hash)
    assert "Failed to satisfy" in str(excinfo.value)
