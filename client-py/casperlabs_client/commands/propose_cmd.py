import sys

from casperlabs_client import CasperLabsClient
from casperlabs_client.utils import guarded_command

NAME: str = "propose"
HELP: str = "[DEPRECATED] Force a node to propose a block based on its accumulated deploys."
OPTIONS = []


@guarded_command
def method(casperlabs_client: CasperLabsClient, args):
    print("Warning: method propose is deprecated.", file=sys.stderr)
    response = casperlabs_client.propose()
    print(f"Success! Block hash: {response.block_hash.hex()}")
