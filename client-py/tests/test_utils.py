import pytest
from casperlabs_client import casper_pb2 as casper
from semver import VersionInfo

from casperlabs_client import utils


@pytest.mark.parametrize(
    "arg_type,hash_,name,sem_ver,error",
    (
        ("payment", None, None, None, None),
        ("session", "1a2c3b4f879e0a42", None, None, TypeError),
        ("session", None, "my_function", None, TypeError),
        ("session", "1a2c3b4f879e0a42", None, VersionInfo(1, 2, 3), None),
        ("session", None, "my_function", VersionInfo(4, 3, 2), None),
    ),
)
def test_error_if_requires_sem_ver_and_missing(arg_type, hash_, name, sem_ver, error):
    if error:
        with pytest.raises(error):
            utils._error_if_requires_sem_ver_and_missing(arg_type, hash_, name, sem_ver)
    else:
        utils._error_if_requires_sem_ver_and_missing(arg_type, hash_, name, sem_ver)


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
