import pytest
import random
from erc20 import Node, Agent, ERC20, last_block_hash, transfer_clx

# This is a pytest test that works with docker node.
#
# To run, start node with:
#
# $ cd ~/CasperLabs/hack/docker
# $ make node-0/up
#
# Then run the test with py.test:
#
# $ py.test test_erc20.py

# ABC is the name of out test ERC20 token
TOKEN_NAME = "ABC"
TOTAL_TOKEN_SUPPLY = 20000
INITIAL_AGENT_CLX_FUNDS = 10 * 10 ** 6


@pytest.fixture()
def node():
    return Node("localhost")


@pytest.fixture()
def faucet():
    return Agent("faucet-account")


@pytest.fixture()
def agents(faucet, node):
    agents = [Agent("account-0"), Agent("account-1"), Agent("account-2")]
    for a in agents:
        transfer_clx(faucet.on(node), a.public_key_hex, INITIAL_AGENT_CLX_FUNDS)
    return agents


def check_total_token_amount(node, abc, deployer, agents, amount):
    n = sum(
        deployer.on(node).call_contract(
            abc.balance(
                deployer.public_key_hex, agent.public_key_hex, last_block_hash(node)
            )
        )
        for agent in agents + [deployer]
    )
    assert n == amount


def test_erc20(node, faucet, agents):
    abc = ERC20(TOKEN_NAME)
    boss = faucet.on(node)

    boss.call_contract(abc.deploy(initial_balance=TOTAL_TOKEN_SUPPLY))

    balance = boss.call_contract(
        abc.balance(faucet.public_key_hex, faucet.public_key_hex, last_block_hash(node))
    )
    # Initially deployer's balance should be equal the total token supply
    assert balance == TOTAL_TOKEN_SUPPLY

    # Transfer n tokens from deployer to each agent
    n = 50
    for agent in agents:
        boss.call_contract(
            abc.transfer(
                deployer_public_hex=faucet.public_key_hex,
                sender_private_key=faucet.private_key,
                recipient_public_key_hex=agent.public_key_hex,
                amount=n,
                block_hash_hex=last_block_hash(node),
            )
        )
        balance = boss.call_contract(
            abc.balance(
                faucet.public_key_hex, agent.public_key_hex, last_block_hash(node)
            )
        )
        assert balance == n

    check_total_token_amount(node, abc, faucet, agents, TOTAL_TOKEN_SUPPLY)

    # Execute few transfers between random agents, check total tokens in the system stays the same
    for i in range(20):
        sender, recipient = random.sample(agents, 2)
        sender.on(node).call_contract(
            abc.transfer(
                deployer_public_hex=faucet.public_key_hex,
                sender_private_key=sender.private_key,
                recipient_public_key_hex=recipient.public_key_hex,
                amount=1,
                block_hash_hex=last_block_hash(node),
            )
        )
        check_total_token_amount(node, abc, faucet, agents, TOTAL_TOKEN_SUPPLY)
