from casperlabs_client import consts
from casperlabs_client.decorators import guarded_command
from casperlabs_client.utils import hexify

NAME: str = "show-deploy"
HELP: str = "View properties of a deploy known by Casper on an existing running node."
OPTIONS = [
    [("hash",), dict(type=str, help="Value of the deploy hash, base16 encoded.")],
    [
        ("-w", "--wait-for-processed"),
        dict(action="store_true", help="Wait for deploy status PROCESSED or DISCARDED"),
    ],
    [
        ("--timeout-seconds",),
        dict(type=int, default=consts.STATUS_TIMEOUT, help="Timeout in seconds"),
    ],
]


@guarded_command
def method(casperlabs_client, args):
    response = casperlabs_client.showDeploy(
        args.hash,
        full_view=False,
        wait_for_processed=args.wait_for_processed,
        timeout_seconds=args.timeout_seconds,
    )
    print(hexify(response))
