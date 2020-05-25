from casperlabs_client.decorators import guarded_command
from casperlabs_client.io import _print_blocks

NAME: str = "show-blocks"
HELP: str = "View list of blocks in the current Casper view on an existing running node."
OPTIONS = [
    [
        ("-d", "--depth"),
        dict(required=True, type=int, help="depth in terms of block height"),
    ]
]


@guarded_command
def method(casperlabs_client, args):
    response = casperlabs_client.showBlocks(args.depth, full_view=False)
    _print_blocks(response)
