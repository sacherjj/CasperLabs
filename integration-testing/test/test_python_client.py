import json
import logging
import os
from pathlib import Path
from pytest import fixture

from casperlabs_client import ABI
from test.cl_node.casperlabs_accounts import GENESIS_ACCOUNT


def resource(fn):
    cur_path = Path(os.path.realpath(__file__)).parent
    while cur_path.name != "integration-testing":
        cur_path = cur_path.parent
    return cur_path / "resources" / fn


def test_args_parser():
    account_hex = "0001000200030004000500060007000800000001000200030004000500060007"
    account_bytes = (
        b"\x00\x01\x00\x02\x00\x03\x00\x04\x00\x05\x00\x06\x00\x07\x00\x08"
        b"\x00\x00\x00\x01\x00\x02\x00\x03\x00\x04\x00\x05\x00\x06\x00\x07"
    )
    u32 = 1024
    u64 = 1234567890
    json_str = json.dumps([{"u32": u32}, {"account": account_hex}, {"u64": u64}])

    assert ABI.args_from_json(json_str) == ABI.args(
        [ABI.u32(1024), ABI.account(account_bytes), ABI.u64(1234567890)]
    )


@fixture()
def genesis_public_signing_key():
    with GENESIS_ACCOUNT.public_key_binary_file() as f:
        yield f


def test_deploy_with_args(one_node_network, genesis_public_signing_key):
    """
    Deploys test contracts that do:

        revert(get_arg(0)); // for u32 and u512

    and

        revert(sum(address_bytes[u8; 32]) + u32); for multiple argument test.

    Tests args get correctly encoded and decoded in the contract.
    """
    node = one_node_network.docker_nodes[0]
    client = node.p_client.client

    nonce = 1
    for wasm, encode in [(resource("test_args_u32.wasm"), ABI.u32), (resource("test_args_u512.wasm"), ABI.u512)]:
        for number in [1, 12, 256, 1024]:
            response, deploy_hash = client.deploy(
                payment=wasm,
                session=wasm,
                public_key=resource("accounts/account-public-genesis.pem"),
                private_key=resource("accounts/account-private-genesis.pem"),
                args=ABI.args([encode(number)]),
                nonce=nonce,
            )
            nonce += 1
            logging.info(f"DEPLOY RESPONSE: {response} deploy_hash: {deploy_hash.hex()}")

            response = client.propose()
            # Need to convert to hex string from bytes
            block_hash = response.block_hash.hex()

            for deploy_info in client.showDeploys(block_hash):
                assert deploy_info.is_error is True
                assert deploy_info.error_message == f"Exit code: {number}"

                # Test show_deploy
                d = client.showDeploy(deploy_info.deploy.deploy_hash.hex())
                assert deploy_info.deploy.deploy_hash == d.deploy.deploy_hash

            logging.info(f"NONCE ===================== {nonce}")

    wasm = resource("test_args_multi.wasm")
    account_hex = "0101010102020202030303030404040405050505060606060707070708080808"
    number = 1000
    total_sum = sum([1, 2, 3, 4, 5, 6, 7, 8]) * 4 + number

    response, deploy_hash = client.deploy(
        payment=wasm,
        session=wasm,
        public_key=resource("accounts/account-public-genesis.pem"),
        private_key=resource("accounts/account-private-genesis.pem"),

        args=ABI.args([ABI.account(bytes.fromhex(account_hex)), ABI.u32(number)]),
        nonce=nonce,
    )
    logging.info(f"DEPLOY RESPONSE: {response} deploy_hash: {deploy_hash.hex()}")
    response = client.propose()

    block_hash = response.block_hash.hex()

    for deploy_info in client.showDeploys(block_hash):
        assert deploy_info.is_error is True
        assert deploy_info.error_message == f"Exit code: {total_sum}"

        # Test show_deploy
        d = client.showDeploy(deploy_info.deploy.deploy_hash.hex())
        assert deploy_info.deploy.deploy_hash == d.deploy.deploy_hash

    for blockInfo in client.showBlocks(10):
        assert blockInfo.status.stats.block_size_bytes > 0

