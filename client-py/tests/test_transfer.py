import pytest
from casperlabs_client import CasperLabsClient, InternalError


@pytest.mark.parametrize("target_account,target_purse", ((None, None), ("abc", "abc")))
def test_not_only_one_target_arguments(target_account, target_purse):
    with pytest.raises(InternalError):
        CasperLabsClient().transfer(
            amount=100, target_purse=target_purse, target_account=target_account
        )
