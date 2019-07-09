import pytest

ffi_test_contracts = [
    ('getcallerdefine.wasm', 'getcallercall.wasm'),
    ('listknownurefsdefine.wasm', 'listknownurefscall.wasm'),
]


def deploy_and_propose_expect_no_errors(node, contract):
    client = node.d_client

    block_hash = node.deploy_and_propose(session_contract=contract, payment_contract=contract)
    r = client.show_deploys(block_hash)[0]
    assert r.is_error is False
    assert r.error_message == ''


@pytest.mark.parametrize("define_contract, call_contract", ffi_test_contracts)
def test_get_caller(one_node_network, define_contract, call_contract):
    node = one_node_network.docker_nodes[0]
    deploy_and_propose_expect_no_errors(node, define_contract)
    deploy_and_propose_expect_no_errors(node, call_contract)
