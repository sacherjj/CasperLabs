import base64

from casperlabs_client.abi import ABI
from casperlabs_client.commands import deploy_cmd
from casperlabs_client.utils import guarded_command, bundled_contract

TRANSFER_TO_ACCOUNT_WASM: str = "transfer_to_account_u512.wasm"
NAME: str = "transfer"
HELP: str = "Transfers funds between accounts"
OPTIONS = [
    [
        ("-a", "--amount"),
        dict(
            required=True,
            default=None,
            type=int,
            help="Amount of motes to transfer. Note: a mote is the smallest, indivisible unit of a token.",
        ),
    ],
    [
        ("-t", "--target-account"),
        dict(
            required=True,
            type=str,
            help="base64 or base16 representation of target account's public key",
        ),
    ],
] + deploy_cmd.OPTIONS_WITH_PRIVATE


@guarded_command
def method(casperlabs_client, args):
    if not any((args.session, args.session_hash, args.session_name, args.session_uref)):
        args.session = bundled_contract(TRANSFER_TO_ACCOUNT_WASM)

    if not args.session_args:
        target_account_bytes = base64.b64decode(args.target_account)
        if len(target_account_bytes) != 32:
            target_account_bytes = bytes.fromhex(args.target_account)
            if len(target_account_bytes) != 32:
                raise Exception(
                    "--target_account must be 32 bytes base64 or base16 encoded"
                )

        args.session_args = ABI.args_to_json(
            ABI.args(
                [
                    ABI.account("account", target_account_bytes),
                    ABI.u512("amount", args.amount),
                ]
            )
        )

    return deploy_cmd.method(casperlabs_client, args)
