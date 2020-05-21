from casperlabs_client import CasperLabsClient
from casperlabs_client.utils import guarded_command, hexify

NAME: str = "query-state"
HELP: str = "Query a value in the global state."
OPTIONS = [
    [
        ("-b", "--block-hash"),
        dict(required=True, type=str, help="Hash of the block to query the state of"),
    ],
    [
        ("-k", "--key"),
        dict(required=True, type=str, help="Base16 encoding of the base key"),
    ],
    [
        ("-p", "--path"),
        dict(
            required=False,
            type=str,
            help="Path to the value to query. Must be of the form 'key1/key2/.../keyn'",
        ),
    ],
    [
        ("-t", "--type"),
        dict(
            required=True,
            choices=("hash", "uref", "address", "local"),
            help=(
                "Type of base key. Must be one of 'hash', 'uref', 'address' or 'local'. "
                "For 'local' key type, 'key' value format is {seed}:{rest}, "
                "where both parts are hex encoded."
            ),
        ),
    ],
]


@guarded_command
def method(casperlabs_client: CasperLabsClient, args):
    response = casperlabs_client.query_state(
        args.block_hash, args.key, args.path or "", getattr(args, "type")
    )
    print(hexify(response))
