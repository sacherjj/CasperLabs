from typing import Dict

from casperlabs_client import consts, reformat, CasperLabsClient
from casperlabs_client.commands.common_options import (
    ALGORITHM_OPTION,
    FROM_OPTION,
    PAYMENT_OPTIONS,
    PRIVATE_KEY_OPTION,
    CHAINNAME_OPTION,
    DEPENDENCIES_OPTION,
    SESSION_OPTIONS,
    TTL_MILLIS_OPTION,
    WAIT_PROCESSED_OPTION,
    TIMEOUT_SECONDS_OPTION,
)
from casperlabs_client.decorators import guarded_command

NAME: str = "deploy"
HELP: str = (
    "Deploy a smart contract source file to Casper on an existing running node. "
    "The deploy will be packaged and sent as a block to the network depending "
    "on the configuration of the Casper instance."
)

OPTIONS = (
    [
        FROM_OPTION,
        CHAINNAME_OPTION,
        DEPENDENCIES_OPTION,
        TTL_MILLIS_OPTION,
        WAIT_PROCESSED_OPTION,
        TIMEOUT_SECONDS_OPTION,
        ALGORITHM_OPTION,
    ]
    + SESSION_OPTIONS
    + PAYMENT_OPTIONS
)
OPTIONS_WITH_PRIVATE = [PRIVATE_KEY_OPTION] + OPTIONS


@guarded_command
def method(casperlabs_client: CasperLabsClient, args: Dict):
    deploy_hash = casperlabs_client.deploy(
        from_addr=args.get("from"),
        payment=args.get("payment"),
        session=args.get("session"),
        public_key=args.get("public_key"),
        private_key=args.get("private_key"),
        session_args=args.get("session_args"),
        payment_args=args.get("payment_args"),
        payment_amount=args.get("payment_amount"),
        payment_hash=args.get("payment_hash"),
        payment_name=args.get("payment_name"),
        payment_uref=args.get("payment_uref"),
        session_hash=args.get("session_hash"),
        session_name=args.get("session_name"),
        session_uref=args.get("session_uref"),
        ttl_millis=args.get("ttl_millis"),
        dependencies=args.get("dependencies"),
        chain_name=args.get("chain_name"),
        algorithm=args.get("algorithm"),
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
