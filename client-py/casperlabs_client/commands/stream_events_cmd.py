import argparse
import base64

from casperlabs_client.utils import guarded_command, jsonify, hexify

NAME: str = "stream-events"
HELP: str = "Stream block and deploy state transition events."
OPTIONS = [
    [("--all",), dict(action="store_true", help="Subscribe to all events")],
    [("--block-added",), dict(action="store_true", help="Block added")],
    [("--block-finalized",), dict(action="store_true", help="Block finalized")],
    [("--deploy-added",), dict(action="store_true", help="Deploy added")],
    [("--deploy-discarded",), dict(action="store_true", help="Deploy discarded")],
    [("--deploy-requeued",), dict(action="store_true", help="Deploy requeued")],
    [("--deploy-processed",), dict(action="store_true", help="Deploy processed")],
    [("--deploy-finalized",), dict(action="store_true", help="Deploy finalized")],
    [("--deploy-orphaned",), dict(action="store_true", help="Deploy orphaned")],
    [
        ("-k", "--account-public-key"),
        dict(
            action="append", help="Filter by (possibly multiple) account public key(s)"
        ),
    ],
    [
        ("-d", "--deploy-hash"),
        dict(action="append", help="Filter by (possibly multiple) deploy hash(es)"),
    ],
    [
        ("-f", "--format"),
        dict(
            required=False,
            default="text",
            choices=("json", "binary", "text"),
            help="Choose output format. Defaults to text representation.",
        ),
    ],
    [
        ("--min-event-id",),
        dict(
            required=False,
            default=0,
            type=int,
            help=(
                "Supports replaying events from a given ID. If the value is 0, it it will subscribe to future events; "
                "if it's non-zero, it will replay all past events from that ID, without subscribing to new. "
                "To catch up with events from the beginning, start from 1."
            ),
        ),
    ],
    [
        ("--max-event-id",),
        dict(
            required=False,
            default=0,
            type=int,
            help="Supports replaying events to a given ID.",
        ),
    ],
]


@guarded_command
def method(casperlabs_client, args):
    subscribed_events = dict(
        all=args.all,
        block_added=args.block_added,
        block_finalized=args.block_finalized,
        deploy_added=args.deploy_added,
        deploy_discarded=args.deploy_discarded,
        deploy_requeued=args.deploy_requeued,
        deploy_processed=args.deploy_processed,
        deploy_finalized=args.deploy_finalized,
        deploy_orphaned=args.deploy_orphaned,
    )
    if not any(subscribed_events.values()):
        raise argparse.ArgumentTypeError("No events chosen")

    stream = casperlabs_client.stream_events(
        account_public_keys=args.account_public_key,
        deploy_hashes=args.deploy_hash,
        min_event_id=args.min_event_id,
        max_event_id=args.max_event_id,
        **subscribed_events,
    )
    for event in stream:
        if args.format == "binary":
            print(base64.b64encode(event.SerializeToString()).decode())
        elif args.format == "json":
            print(jsonify(event))
        else:
            print(hexify(event))
