import pytest
import random
from erc20 import Node, Agent, ERC20, DeployedERC20, transfer_clx

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
INITIAL_AGENT_CLX_FUNDS = 10 ** 7


@pytest.fixture()
def node():
    return Node("localhost")


@pytest.fixture()
def faucet():
    return Agent("faucet-account")


@pytest.fixture()
def agents(faucet, node):
    agents = [Agent("account-0"), Agent("account-1"), Agent("account-2")]
    return agents


def check_total_token_amount(node, abc, deployer, agents, amount):
    n = sum(
        deployer.on(node).call_contract(abc.balance(agent.public_key_hex))
        for agent in agents + [deployer]
    )
    assert n == amount


def random_node(nodes):
    return random.sample(nodes, 1)[0]


def initialize_erc20_simulation(
    node,
    faucet,
    agents,
    token_name,
    total_token_supply,
    initial_agent_clx_funds,
    tokens_per_agent,
):
    """
    Deploy ERC20 smart contract from the faucet account.
    Transfer initial_agent_clx_funds CLX to each agent.
    Transfer tokens_per_agent ERC20 tokens to each agent.
    """
    boss = faucet.on(node)
    abc = boss.call_contract(
        ERC20(token_name).deploy(initial_balance=total_token_supply)
    )

    balance = boss.call_contract(abc.balance(faucet.public_key_hex))

    # Initially deployer's balance should be equal the total token supply
    assert balance == total_token_supply

    for agent in agents:
        transfer_clx(faucet.on(node), agent.public_key_hex, initial_agent_clx_funds)

    # Transfer tokens from deployer to agents
    for agent in agents:
        boss.call_contract(
            abc.transfer(
                sender_private_key=faucet.private_key,
                recipient_public_key_hex=agent.public_key_hex,
                amount=tokens_per_agent,
            )
        )
        balance = boss.call_contract(abc.balance(agent.public_key_hex))
        assert balance == tokens_per_agent

    check_total_token_amount(node, abc, faucet, agents, total_token_supply)


def transfer_tokens_between_agents(
    nodes,
    faucet,
    agents,
    token_name,
    total_token_supply,
    number_of_iterations,
    max_transfer,
):
    abc = DeployedERC20.create(faucet.on(nodes[0]), token_name)
    # Execute few transfers between random agents, check total tokens in the system stays the same
    for i in range(number_of_iterations):
        node = random_node(nodes)
        sender, recipient = random.sample(agents, 2)
        sender.on(node).call_contract(
            abc.transfer(
                sender_private_key=sender.private_key,
                recipient_public_key_hex=recipient.public_key_hex,
                amount=random.randint(1, max_transfer),
            )
        )
        check_total_token_amount(node, abc, faucet, agents, total_token_supply)


def run_erc20_simulation(
    nodes,
    faucet,
    agents,
    token_name,
    total_token_supply,
    initial_agent_clx_funds,
    tokens_per_agent,
    number_of_iterations,
    max_transfer,
):
    initialize_erc20_simulation(
        nodes[0],
        faucet,
        agents,
        token_name,
        total_token_supply,
        initial_agent_clx_funds,
        tokens_per_agent,
    )
    transfer_tokens_between_agents(
        nodes,
        faucet,
        agents,
        token_name,
        total_token_supply,
        number_of_iterations,
        max_transfer,
    )


def test_erc20(node, faucet, agents):
    run_erc20_simulation(
        [node],
        faucet,
        agents,
        TOKEN_NAME,
        TOTAL_TOKEN_SUPPLY,
        INITIAL_AGENT_CLX_FUNDS,
        100,
        20,
        3,
    )
