import pytest

from casperlabs_client import CasperLabsClient, InternalError
from casperlabs_client.consts import ACCOUNT_PRIVATE_KEY_FILENAME


def test_basic_transfer_to_node_comm_failure(account_keys_directory):
    client = CasperLabsClient()
    with pytest.raises(InternalError) as excinfo:
        _ = client.transfer(
            from_addr="1212121212121212121212121212121212121212121212121212121212121212",
            target_account="0000000000000000000000000000000000000000000000000000000000000000",
            amount=10000,
            private_key=account_keys_directory / ACCOUNT_PRIVATE_KEY_FILENAME,
        )
    assert "failed to connect" in str(excinfo.value)
