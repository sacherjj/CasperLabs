from test.cl_node.casperlabsnode import BONDING_CONTRACT, UNBONDING_CONTRACT
from test.cl_node.client_parser import parse_show_block


def bond_to_the_network(network, contract, bond_amount, is_deploy_info_err=False):
    network.add_new_node_to_network()
    assert len(network.docker_nodes) == 2, "Total number of nodes should be 2."
    node0, node1 = network.docker_nodes
    block_hash = node1.bond(
        session_contract=contract, payment_contract=contract, amount=bond_amount
    )
    return block_hash


def test_bonding(one_node_network):
    """
    Feature file: consensus.feature
    Scenario: Bonding a validator node to an existing network.
    """
    block_hash = bond_to_the_network(one_node_network, BONDING_CONTRACT, bond_amount=1)
    assert block_hash is not None
    node1 = one_node_network.docker_nodes[1]
    block1 = node1.client.show_block(block_hash)
    block_ds = parse_show_block(block1)
    public_key = node1.from_address
    item = list(
        filter(
            lambda x: x.stake == 1 and x.validator_public_key == public_key,
            block_ds.summary[0].header[0].state[0].bonds,
        )
    )
    assert len(item) == 1


def test_invalid_bonding(one_node_network):
    """
    Feature file: consensus.feature
    Scenario: Bonding a validator node to an existing network.
    """
    # 190 is current total staked amount.
    block_hash = bond_to_the_network(
        one_node_network, BONDING_CONTRACT, bond_amount=(190 * 1000) + 1
    )
    assert block_hash is not None
    node1 = one_node_network.docker_nodes[1]
    block1 = node1.client.show_block(block_hash)
    block_ds = parse_show_block(block1)
    public_key = node1.from_address
    item = list(
        filter(
            lambda x: x.stake == ((190 * 1000) + 1)
            and x.validator_public_key == public_key,
            block_ds.summary[0].header[0].state[0].bonds,
        )
    )
    assert len(item) == 0


def test_unbonding(one_node_network):
    """
    Feature file: consensus.feature
    Scenario: unbonding a bonded validator node from an existing network.
    """
    bond_to_the_network(one_node_network, BONDING_CONTRACT, 1)
    node0, node1 = one_node_network.docker_nodes
    public_key = node1.from_address
    block_hash2 = node1.unbond(
        session_contract=UNBONDING_CONTRACT,
        payment_contract=UNBONDING_CONTRACT,
        maybe_amount=None,
    )

    assert block_hash2 is not None
    block2 = node1.client.show_block(block_hash2)
    block_ds = parse_show_block(block2)
    item = list(
        filter(
            lambda x: x.stake == 1 and x.validator_public_key == public_key,
            block_ds.summary[0].header[0].state[0].bonds,
        )
    )
    assert len(item) == 0


def test_invalid_unbonding(one_node_network):
    """
    Feature file: consensus.feature
    Scenario: unbonding a bonded validator node from an existing network.
    """
    bond_to_the_network(one_node_network, BONDING_CONTRACT, bond_amount=3000)
    node0, node1 = one_node_network.docker_nodes
    block_hash2 = node1.unbond(
        session_contract=UNBONDING_CONTRACT,
        payment_contract=UNBONDING_CONTRACT,
        amount=185,
    )

    assert block_hash2 is not None
    block2 = node1.client.show_block(block_hash2)
    block_ds = parse_show_block(block2)
    item = list(
        filter(
            lambda x: x.stake == 3000 and x.validator_public_key == node1.from_address,
            block_ds.summary[0].header[0].state[0].bonds,
        )
    )
    assert len(item) == 1
