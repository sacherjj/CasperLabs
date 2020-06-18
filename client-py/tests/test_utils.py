import pytest
from casperlabs_client import casper_pb2 as casper

from casperlabs_client import utils


@pytest.mark.parametrize(
    "key_type,key_variant,exception_type",
    (
        ("hash", casper.StateQuery.KeyVariant.HASH, None),
        ("address", casper.StateQuery.KeyVariant.ADDRESS, None),
        ("local", casper.StateQuery.KeyVariant.LOCAL, None),
        ("", None, ValueError),
        ("bad_key", None, ValueError),
    ),
)
def test_key_variant(key_type, key_variant, exception_type):
    if key_variant:
        assert utils.key_variant(key_type) == key_variant
    else:
        with pytest.raises(exception_type):
            utils.key_variant(key_type)
