import dataclasses
import os
import re
import random
import string
import tempfile
from pathlib import Path

from .errors import (
    UnexpectedProposeOutputFormatError,
    UnexpectedShowBlocksOutputFormatError,
)
from casperlabs_client import ABI


class Contract:
    ADD_ASSOCIATED_KEY = "add_associated_key.wasm"
    ARGS = "test_args.wasm"
    ARGS_MULTI = "test_args_multi.wasm"
    ARGS_U32 = "test_args_u32.wasm"
    ARGS_U512 = "test_args_u512.wasm"
    BONDINGCALL = "test_bondingcall.wasm"
    COMBINEDCONTRACTSDEFINE = "test_combinedcontractsdefine.wasm"
    COUNTERCALL = "test_countercall.wasm"
    COUNTERDEFINE = "test_counterdefine.wasm"
    DIRECT_REVERT_CALL = "test_direct_revert_call.wasm"
    DIRECT_REVERT_DEFINE = "test_direct_revert_define.wasm"
    ENDLESS_LOOP = "endless_loop.wasm"
    ERR_STANDARD_PAYMENT = "err_standard_payment.wasm"
    ERR_TRANSFER_TO_ACCOUNT = "err_transfer_to_account.wasm"
    FINALIZE_PAYMENT = "test_finalize_payment.wasm"
    GETCALLERCALL = "getcallercall.wasm"
    GETCALLERDEFINE = "getcallerdefine.wasm"
    HELLONAME = "test_helloname.wasm"
    HELLOWORLD = "test_helloworld.wasm"
    LISTKNOWNUREFSCALL = "listknownurefscall.wasm"
    LISTKNOWNUREFSDEFINE = "listknownurefsdefine.wasm"
    MAILINGLISTCALL = "test_mailinglistcall.wasm"
    MAILINGLISTDEFINE = "test_mailinglistdefine.wasm"
    PAYMENT_PURSE = "test_payment_purse.wasm"
    REFUND_PURSE = "test_refund_purse.wasm"
    REMOVE_ASSOCIATED_KEY = "remove_associated_key.wasm"
    SET_KEY_THRESHOLDS = "set_key_thresholds.wasm"
    STANDARD_PAYMENT = "standard_payment.wasm"
    SUBCALL_REVERT_CALL = "test_subcall_revert_call.wasm"
    SUBCALL_REVERT_DEFINE = "test_subcall_revert_define.wasm"
    TRANSFER_TO_ACCOUNT = "transfer_to_account.wasm"
    TRANSFER_TO_ACCOUNT_1 = "test_transfer_to_account_1.wasm"
    TRANSFER_TO_ACCOUNT_1_CALL = "test_transfer_to_account_1_call.wasm"
    TRANSFER_TO_ACCOUNT_2 = "test_transfer_to_account_2.wasm"
    TRANSFER_TO_ACCOUNT_2_CALL = "test_transfer_to_account_2_call.wasm"
    UNBONDINGCALL = "test_unbondingcall.wasm"
    UPDATE_ASSOCIATED_KEY = "update_associated_key.wasm"


# # Args  key: account, weight: u32
# ADD_ASSOCIATED_KEY_CONTRACT = "add_associated_key.wasm"
# # Args  key: account, amount: u32
# TRANSFER_TO_ACCOUNT_CONTRACT = "transfer_to_account.wasm"
# HELLO_NAME_CONTRACT = "test_helloname.wasm"
# HELLO_WORLD = "test_helloworld.wasm"
# COUNTER_CALL = "test_countercall.wasm"
# MAILING_LIST_CALL = "test_mailinglistcall.wasm"
# COMBINED_CONTRACT = "test_combinedcontractsdefine.wasm"
# BONDING_CONTRACT = "test_bondingcall.wasm"
# UNBONDING_CONTRACT = "test_unbondingcall.wasm"
# INVALID_BONDING_CONTRACT = "test_invalid_bondingcall.wasm"
# INVALID_UNBONDING_CONTRACT = "test_invalid_unbondingcall.wasm"
# PAYMENT_PURSE_CONTRACT = "test_payment_purse.wasm"
# PAYMENT_CONTRACT = "standard_payment.wasm"

MAX_PAYMENT_COST = 10000000  # ten million
MAX_PAYMENT_ABI = ABI.args([ABI.u512(MAX_PAYMENT_COST)])
CONV_RATE = 10


@dataclasses.dataclass(eq=True, frozen=True)
class KeyPair:
    private_key: str
    public_key: str


def testing_root_path() -> Path:
    cur_path = Path(os.path.realpath(__file__)).parent
    while cur_path.name != "integration-testing":
        cur_path = cur_path.parent
    return cur_path


def random_string(length: int) -> str:
    return "".join(random.choice(string.ascii_letters) for _ in range(length)).lower()


def make_tempfile(prefix: str, content: str) -> str:
    fd, path = tempfile.mkstemp(dir="/tmp", prefix=prefix)

    with os.fdopen(fd, "w") as tmp:
        tmp.write(content)

    return path


def make_tempdir(prefix: str) -> str:
    return tempfile.mkdtemp(dir="/tmp", prefix=prefix)


def extract_block_count_from_show_blocks(show_blocks_output: str) -> int:
    lines = show_blocks_output.splitlines()
    prefix = "count: "
    interesting_lines = [l for l in lines if l.startswith(prefix)]
    if len(interesting_lines) != 1:
        raise UnexpectedShowBlocksOutputFormatError(show_blocks_output)
    line = interesting_lines[0]
    count = line[len(prefix) :]
    try:
        result = int(count)
    except ValueError:
        raise UnexpectedShowBlocksOutputFormatError(show_blocks_output)
    return result


def extract_block_hash_from_propose_output(propose_output: str):
    """We're getting back something along the lines of:

    Response: Success! Block a91208047c... created and added.\n
    """
    match = re.match(
        r"Response: Success! Block ([0-9a-f]+) created and added.",
        propose_output.strip(),
    )
    if match is None:
        raise UnexpectedProposeOutputFormatError(propose_output)
    return match.group(1)
