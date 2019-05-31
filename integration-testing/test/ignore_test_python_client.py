from .cl_node.wait import (wait_for_blocks_count_at_least, )

import pytest
import casper_client
import logging
import ed25519

def resource(file_name):
    return f'resources/{file_name}'


def test_python_client(one_node_network):
    """
    """

    signing_key_file, verifying_key_file = 'signing_key', 'verifying_key'
    signing_key, verifying_key = ed25519.create_keypair()
    with open(signing_key_file, 'wb') as f: f.write(signing_key.to_bytes())
    with open(verifying_key_file, 'wb') as f: f.write(verifying_key.to_bytes())

    node = one_node_network.docker_nodes[0]
    client = casper_client.CasperClient()

    for wasm in map(resource, ("test_helloname.wasm", "test_helloworld.wasm")):
        response = client.deploy(from_addr=32*b'0', payment=wasm, session=wasm, public_key=verifying_key_file, private_key=signing_key_file)
        response = client.propose()

    number_of_blocks = node.get_blocks_count(100)

    wait_for_blocks_count_at_least(node, 2, 2, node.config.command_timeout)

    assert node.get_blocks_count(100) == number_of_blocks
