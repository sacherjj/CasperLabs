import pytest
from casperlabs_client import utils
from semver import VersionInfo


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
def test_requires_sem_ver(arg_type, hash_, name, sem_ver, error):
    if error:
        with pytest.raises(error):
            utils._requires_sem_ver(arg_type, hash_, name, sem_ver)
    else:
        utils._requires_sem_ver(arg_type, hash_, name, sem_ver)
