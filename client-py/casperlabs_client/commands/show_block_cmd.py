from casperlabs_client import CasperLabsClient
from casperlabs_client.decorators import guarded_command
from casperlabs_client.io import _print_block

NAME: str = "show-block"
HELP: str = (
    "View properties of a block known by Casper on an existing running node. "
    "Output includes: parent hashes, storage contents of the tuplespace."
)
OPTIONS = [[("hash",), dict(type=str, help="the hash value of the block")]]


@guarded_command
def method(casperlabs_client: CasperLabsClient, args):
    response = casperlabs_client.show_block(args.hash, full_view=True)
    return _print_block(response)
