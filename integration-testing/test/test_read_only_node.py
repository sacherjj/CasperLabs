from pytest import raises

from test.cl_node.common import PAYMENT_CONTRACT, MAX_PAYMENT_ABI


def test_read_only_node_does_not_accept_deploy(read_only_node_network):
    node = read_only_node_network.docker_nodes[0]
    account = node.genesis_account
    with raises(Exception) as exinfo:
        node.p_client.deploy(
            from_address=account.public_key_hex,
            session_contract="test_helloname.wasm",
            public_key=account.public_key_path,
            private_key=account.private_key_path,
            payment_contract=PAYMENT_CONTRACT,
            payment_args=MAX_PAYMENT_ABI,
        )
    assert "FAILED_PRECONDITION: Node is in read-only mode" in str(exinfo.value)
