from casperlabs_client.decorators import guarded_command

NAME: str = "balance"
HELP: str = "Returns the balance of the account at the specified block."
OPTIONS = [
    [
        ("-a", "--address"),
        dict(required=True, type=str, help="Account's public key in hex."),
    ],
    [
        ("-b", "--block-hash"),
        dict(required=True, type=str, help="Hash of the block to query the state of"),
    ],
]


@guarded_command
def method(casperlabs_client, args):
    response = casperlabs_client.balance(args.address, args.block_hash)
    print(response)
