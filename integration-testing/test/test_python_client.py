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

    signing_public_key_file, signing_private_key_file = 'signing_key', 'verifying_key'

    write_binary(signing_public_key_file, node.signing_public_key())
    write_binary(signing_private_key_file, node.signing_private_key())

    wasm = "transfer.wasm"
    amount = 1234
    new_account_private_key, new_account_public_key = ed25519.create_keypair()
    target_account = new_account_public_key.to_bytes()

    logging.info(f"=========================================")
    logging.info(f"Transferring {amount} to {target_account.hex()}") 
    logging.info(f"=========================================")

    response = client.deploy(payment_contract = wasm,
                             session_contract = wasm,
                             public_key = signing_public_key_file,
                             private_key = signing_private_key_file,
                             args = [ABI.account(target_account), ABI.u64(1234)])
    response = client.propose()
    assert response.success is True
    # "Success! Block 3c543cb5fa... created and added."
    assert 'Success!' in response.message

    block_hash_prefix = response.message.split()[2][0:10]

    for deploy in node.d_client.show_deploys(block_hash_prefix):
        #assert deploy.is_error is False
        logging.info(f"DEPLOY ERROR MESSAGE: {deploy.error_message}") 

