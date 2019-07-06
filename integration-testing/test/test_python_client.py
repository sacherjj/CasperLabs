from .cl_node.wait import (wait_for_blocks_count_at_least, )

import pytest
import casper_client
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

    for wasm in ("test_helloname.wasm", "test_helloworld.wasm"):
        response = client.deploy(payment_contract = wasm,
                                 session_contract = wasm,
                                 public_key = verifying_key_file,
                                 private_key = signing_key_file,
                                )
        response = client.propose()
        assert response.success is True
        # "Success! Block 3c543cb5fa... created and added."
        assert 'Success!' in response.message

