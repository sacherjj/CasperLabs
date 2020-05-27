import pytest

from casperlabs_client import CasperLabsClient, InternalError


def test_simple_deploy_build_to_node_comm_failure(account_keys_directory):
    client = CasperLabsClient()
    with pytest.raises(InternalError) as excinfo:
        _ = client.deploy(
            from_addr=b"12121212121212121212121212121212", session_name="contract_name"
        )
    assert "failed to connect" in str(excinfo.value)
