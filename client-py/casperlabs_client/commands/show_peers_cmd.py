from typing import Dict

from casperlabs_client import CasperLabsClient, utils
from casperlabs_client.decorators import guarded_command

NAME: str = "show-peers"
HELP: str = "Show peers connected to the node."
OPTIONS = []


@guarded_command
def method(casperlabs_client: CasperLabsClient, args: Dict):
    peers = casperlabs_client.show_peers()
    i = 0
    for i, node in enumerate(peers, 1):
        print(f"------------- node {i} ---------------")
        print(utils.hexify(node))
    print("-----------------------------------------------------")
    print(f"count: {i}")
