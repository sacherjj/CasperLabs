from casperlabs_client import CasperLabsClient


def test_make_deploy(account_keys_directory):
    _ = CasperLabsClient().make_deploy(
        from_addr=b"00000000000000000000000000000000", session_name="contract_name"
    )
