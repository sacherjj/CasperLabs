from casperlabs_client.decorators import guarded_command
from casperlabs_client.utils import hexify

NAME: str = "show-peers"
HELP: str = "Show peers connected to the node."
OPTIONS = []


@guarded_command
def method(casperlabs_client, args):
    peers = casperlabs_client.show_peers()
    i = 0
    for i, node in enumerate(peers, 1):
        print(f"------------- node {i} ---------------")
        print(hexify(node))
    print("-----------------------------------------------------")
    print(f"count: {i}")
