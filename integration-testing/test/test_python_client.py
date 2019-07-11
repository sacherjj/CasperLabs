from casper_client import ABI

import logging


def write_binary(file_name, b):
    with open(file_name, 'wb') as f:
        f.write(b)


def test_args_parser():
    json = """[{"u32":1024}, {"account":"00000000000000000000000000000000"}, {"u64":1234567890}]"""
    assert ABI.args_from_json(json) == ABI.args([ABI.u32(1024),
                                                 ABI.account(b'00000000000000000000000000000000'),
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

    wasm = "test_args.wasm"
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
        block_info = client.show_block(block_hash)

        for deploy_info in client.show_deploys(block_hash):
            assert deploy_info.is_error is True
            assert deploy_info.error_message == f'Exit code: {number}'

            # Test show_deploy
            d = client.show_deploy(deploy_info.deploy.deploy_hash.hex())
            assert deploy_info.deploy.deploy_hash == d.deploy.deploy_hash

    for blockInfo in client.show_blocks(10):
        assert blockInfo.status.stats.block_size_bytes > 0

