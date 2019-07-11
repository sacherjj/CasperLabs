import json
import logging
import os

from casper_client import ABI


def write_binary(file_name, b):
    with open(file_name, 'wb') as f:
        f.write(b)


def test_args_parser():
    account_hex = "0001000200030004000500060007000800000001000200030004000500060007"
    account_bytes = b'\x00\x01\x00\x02\x00\x03\x00\x04\x00\x05\x00\x06\x00\x07\x00\x08' \
                    b'\x00\x00\x00\x01\x00\x02\x00\x03\x00\x04\x00\x05\x00\x06\x00\x07'
    u32 = 1024
    u64 = 1234567890
    json_str = json.dumps([{'u32': u32},
                           {'account': account_hex},
                           {'u64': u64}])

    assert ABI.args_from_json(json_str) == ABI.args([ABI.u32(1024),
                                                     ABI.account(account_bytes),
                                                     ABI.u64(1234567890)])


def test_deploy_with_args(one_node_network):
    """
    Deploys a test contract that does:

        revert(get_arg(0));

    Tests args get correctly encoded and decoded in the contract.
    """
    node = one_node_network.docker_nodes[0]
    client = node.p_client

    signing_public_key_file = 'signing_key'
    # signing_private_key_file = 'verifying_key'

    write_binary(signing_public_key_file, node.signing_public_key())

    # We do not have raw private keys with .pem certificate files
    # removing this to not sign deploy at this time.
    # write_binary(signing_private_key_file, node.signing_private_key())

    wasm = "test_args_u32.wasm"
    for number in [12, 256, 1024]:
        response, deploy_hash = client.deploy(payment_contract=wasm,
                                              session_contract=wasm,
                                              public_key=signing_public_key_file,
                                              # private_key=signing_private_key_file,
                                              args=ABI.args([ABI.u32(number)])
                                              )
        logging.info(f"DEPLOY RESPONSE: {response} deploy_hash: {deploy_hash.hex()}")

        response = client.propose()
        # Need to convert to hex string from bytes
        block_hash = response.block_hash.hex()

        for deploy_info in client.show_deploys(block_hash):
            assert deploy_info.is_error is True
            assert deploy_info.error_message == f'Exit code: {number}'

            # Test show_deploy
            d = client.show_deploy(deploy_info.deploy.deploy_hash.hex())
            assert deploy_info.deploy.deploy_hash == d.deploy.deploy_hash

    # Testing multiple args without rebuilding network.
    wasm = "test_args_multi.wasm"
    account_hex = '0101010102020202030303030404040405050505060606060707070708080808'
    number = 1000
    total_sum = sum([1, 2, 3, 4, 5, 6, 7, 8]) * 4 + number

    json_args = json.dumps([{'account': account_hex}, {'u32': number}])

    response, deploy_hash = client.deploy(payment_contract=wasm,
                                          session_contract=wasm,
                                          public_key=signing_public_key_file,
                                          args=ABI.args_from_json(json_args)
                                          )
    logging.info(f"DEPLOY RESPONSE: {response} deploy_hash: {deploy_hash.hex()}")

    response = client.propose()

    block_hash = response.block_hash.hex()

    for deploy_info in client.show_deploys(block_hash):
        assert deploy_info.is_error is True
        assert deploy_info.error_message == f'Exit code: {total_sum}'

        # Test show_deploy
        d = client.show_deploy(deploy_info.deploy.deploy_hash.hex())
        assert deploy_info.deploy.deploy_hash == d.deploy.deploy_hash

    for blockInfo in client.show_blocks(10):
        assert blockInfo.status.stats.block_size_bytes > 0

    # TODO: Fix so removes on failure too...
    # Seems to lose directory when I put this out of function.  Not time to fix better now.
    os.remove(signing_public_key_file)
