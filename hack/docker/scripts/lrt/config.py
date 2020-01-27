import json
from erc20 import Agent, Node
import casperlabs_client


DEFAULT_CONFIG = """
{
    "agents": [["account-0"], ["account-1"], ["account-2"]],
    "nodes": [["localhost"]],
    "erc20_deployer": ["faucet-account"],
    "token_name": "ABC",
    "total_token_supply": 200000,
    "tokens_per_agent": 10000,
    "max_transfer": 100,
    "initial_agent_clx_funds": 100000000
}
"""


class Configuration:
    def __init__(self, dictionary):
        self.dictionary = dictionary

    @property
    def erc20_deployer(self):
        deployer = self.dictionary["erc20_deployer"]
        return Agent(deployer[0])

    @property
    def agents(self):
        return [Agent(agent[0]) for agent in self.dictionary["agents"]]

    def _make_node(self, node_config):
        host = node_config[0]
        port = casperlabs_client.DEFAULT_PORT
        try:
            port = node_config[1]
        except IndexError:
            pass
        return Node(host, port)

    @property
    def nodes(self):
        return [
            self._make_node(node_config) for node_config in self.dictionary["nodes"]
        ]

    def __getattr__(self, name):
        return self.dictionary[name]


def default_config():
    return Configuration(json.loads(DEFAULT_CONFIG))
