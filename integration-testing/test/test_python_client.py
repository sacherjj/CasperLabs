from .cl_node.wait import (wait_for_blocks_count_at_least, )
from casper_client import ABI

import pytest
import logging
import ed25519


def test_python_client(one_node_network):
    """
    """
    # TODO: Use a pregenerated key
    signing_key_file, verifying_key_file = 'signing_key', 'verifying_key'
    signing_key, verifying_key = ed25519.create_keypair()
    with open(signing_key_file, 'wb') as f: f.write(signing_key.to_bytes())
    with open(verifying_key_file, 'wb') as f: f.write(verifying_key.to_bytes())

    node = one_node_network.docker_nodes[0]
    client = node.p_client

    new_account_private_key, new_account_public_key = ed25519.create_keypair()

    wasm = "transfer.wasm"
    response = client.deploy(payment_contract = wasm,
                             session_contract = wasm,
                             public_key = verifying_key_file,
                             private_key = signing_key_file,
                             args = [
                                     ABI.account(new_account_public_key.to_bytes()), # account
                                     ABI.u64(1234),                                  # amount
                                    ],)
    response = client.propose()
    assert response.success is True
    # "Success! Block 3c543cb5fa... created and added."
    assert 'Success!' in response.message

