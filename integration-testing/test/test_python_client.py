from .cl_node.wait import (wait_for_blocks_count_at_least, )
from casper_client import ABI

import pytest
import logging
import ed25519

import logging


def write_binary(file_name, b):
    with open(file_name, 'wb') as f:
        f.write(b)


def test_python_client(one_node_network):
    """
    """
    node = one_node_network.docker_nodes[0]
    client = node.p_client

    signing_public_key_file = 'signing_key'
    signing_private_key_file = 'verifying_key'

    write_binary(signing_public_key_file, node.signing_public_key())
    write_binary(signing_private_key_file, node.signing_private_key())

    wasm = "test_helloname.wasm"
    response, deploy_hash = client.deploy(payment_contract = wasm,
                                          session_contract = wasm,
                                          public_key = signing_public_key_file,
                                          #private_key = signing_private_key_file,
                                          #args = [ABI.u32(12)]
                                          )
    logging.info(f"DEPLOY RESPONSE: {response} deploy_hash: {deploy_hash.hex()}")
                            
    response = client.propose()
    assert response.success is True
    # "Success! Block 3c543cb5fa... created and added."
    assert 'Success!' in response.message
    logging.info(f"PROPOSE RESPONSE: {response}")

    block_hash_prefix = response.message.split()[2][:10]
    for deploy in node.d_client.show_deploys(block_hash_prefix):
        assert deploy.is_error is False
        #logging.info(f"DEPLOY ERROR MESSAGE: {deploy.error_message}") 

