import pytest
from erc20 import Node, Agent
from simulate_erc20 import run_erc20_simulation


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
