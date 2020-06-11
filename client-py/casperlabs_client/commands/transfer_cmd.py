from typing import Dict

from casperlabs_client import CasperLabsClient, consts, reformat
from casperlabs_client.commands.common_options import (
    FROM_OPTION,
    PAYMENT_OPTIONS,
    CHAINNAME_OPTION,
    SESSION_OPTIONS,
    DEPENDENCIES_OPTION,
    TTL_MILLIS_OPTION,
    PRIVATE_KEY_OPTION,
    WAIT_PROCESSED_OPTION,
    TIMEOUT_SECONDS_OPTION,
)
from casperlabs_client.decorators import guarded_command


TRANSFER_TO_ACCOUNT_WASM: str = "transfer_to_account_u512.wasm"
NAME: str = "transfer"
HELP: str = "Transfers funds between accounts"
OPTIONS = (
    [
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
        FROM_OPTION,
        CHAINNAME_OPTION,
        DEPENDENCIES_OPTION,
        TTL_MILLIS_OPTION,
        WAIT_PROCESSED_OPTION,
        TIMEOUT_SECONDS_OPTION,
        PRIVATE_KEY_OPTION,
    ]
    + SESSION_OPTIONS
    + PAYMENT_OPTIONS
)


@guarded_command
def method(casperlabs_client: CasperLabsClient, args: Dict):
    deploy_hash = casperlabs_client.transfer(
        target_account=args.get("target_account"),
        amount=args.get("amount"),
        from_addr=args.get("from_addr"),
        payment=args.get("payment"),
        public_key=args.get("public_key"),
        private_key=args.get("private_key"),
        payment_args=args.get("payment_args"),
        payment_amount=args.get("payment_amount"),
        payment_hash=args.get("payment_hash"),
        payment_name=args.get("payment_name"),
        payment_uref=args.get("payment_uref"),
        ttl_millis=args.get("ttl_millis"),
        dependencies=args.get("dependencies"),
        chain_name=args.get("chain_name"),
    )
    print(f"Success! Deploy {deploy_hash} deployed")
    if args.get("wait_for_processed", False):
        deploy_info = casperlabs_client.show_deploy(
            deploy_hash,
            full_view=False,
            wait_for_processed=True,
            timeout_seconds=args.get("timeout_seconds", consts.STATUS_TIMEOUT),
        )
        print(reformat.hexify(deploy_info))
