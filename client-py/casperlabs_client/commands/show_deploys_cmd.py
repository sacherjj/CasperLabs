from casperlabs_client.decorators import guarded_command
from casperlabs_client.io import _print_blocks

NAME: str = "show-deploys"
HELP: str = "View deploys included in a block."
OPTIONS = [[("hash",), dict(type=str, help="Value of the block hash, base16 encoded.")]]


@guarded_command
def method(casperlabs_client, args):
    response = casperlabs_client.showDeploys(args.hash, full_view=False)
    _print_blocks(response, element_name="deploy")
