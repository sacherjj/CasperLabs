from casperlabs_local_net.casperlabs_accounts import GENESIS_ACCOUNT
from casperlabs_local_net.cli import DockerCLI
from casperlabs_local_net.common import Contract
from casperlabs_local_net.wait import wait_for_no_new_deploys


def test_invalid_bigint(one_node_network_fn):
    # Test covering fix for NODE-1182
    # Use a malformed BigInt contract argument
    node = one_node_network_fn.docker_nodes[0]
    cli = DockerCLI(node)
    session_wasm = cli.resource(Contract.ARGS_U512)

    # Send in u512 with invalid string format, surrounded []
    args = '[{"name": "arg_u512", "value": {"big_int": {"value": "[1000]", "bit_width": 512}}}]'
    # fmt: off
    deploy_hash = cli("deploy",
                      "--private-key", cli.private_key_path(GENESIS_ACCOUNT),
                      "--payment-amount", 10000000,
                      "--session", session_wasm,
                      "--session-args", cli.format_json_str(args))
    # fmt: on

    wait_for_no_new_deploys(node)

    status = node.d_client.show_deploy(deploy_hash).status
    assert "'state': 'DISCARDED'" in str(status)
    assert (
        "'message': 'Error parsing deploy arguments: InvalidBigIntValue([1000])'"
        in str(status)
    )

    # Send in u512 valid as 1000.
    args = '[{"name": "arg_u512", "value": {"big_int": {"value": "1000", "bit_width": 512}}}]'
    # fmt: off
    deploy_hash = cli("deploy",
                      "--private-key", cli.private_key_path(GENESIS_ACCOUNT),
                      "--payment-amount", 10000000,
                      "--session", session_wasm,
                      "--session-args", cli.format_json_str(args))
    # fmt: on

    node.wait_for_deploy_processed_and_get_block_hash(deploy_hash, on_error_raise=False)

    result = node.d_client.show_deploy(deploy_hash)

    # User(code) in revert adds 65536 to the 1000
    assert f"Exit code: {1000 + 65536}" in str(result)
