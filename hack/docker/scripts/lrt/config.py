import json
from erc20 import Agent, Node
import casperlabs_client

DEFAULT_CONFIG = """
{
    "agents": ["account-0", "account-1", "account-2"],
    "nodes": [{"host": "localhost", "port": 40401},
              {"host": "localhost", "port": 40411},
              {"host": "localhost", "port": 40421}],
    "erc20_deployer": "faucet-account",
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
        return Agent(deployer)

    @property
    def agents(self):
        return [Agent(d) for d in self.dictionary["agents"]]

    @property
    def nodes(self):
        def make_node(node_config):
            host = "localhost"
            port = casperlabs_client.DEFAULT_PORT
            if type(node_config) == str:
                host = node_config
            else:
                host = node_config.get("host", host)
                port = node_config.get("port", port)
            return Node(host, port)

        return [make_node(cfg) for cfg in self.dictionary["nodes"]]

    def __getattr__(self, name):
        return self.dictionary[name]

    @staticmethod
    def default():
        return Configuration(json.loads(DEFAULT_CONFIG))

    @staticmethod
    def read(file_name):
        with open(file_name) as f:
            d = Configuration.default().dictionary
            d.update(json.load(f))
            return Configuration(d)
