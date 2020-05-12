import pytest
import semver
import argparse

from casperlabs_client.arg_types import sem_ver


@pytest.mark.parametrize(
    "sem_ver_str,values_tuple",
    (
        ("1.2.3", (1, 2, 3)),
        ("0.0.0", (0, 0, 0)),
        ("123.45.23", (123, 45, 23)),
        ("0.0.2", (0, 0, 2)),
    ),
)
def test_good_sem_ver(sem_ver_str, values_tuple):
    sv = semver.VersionInfo.parse(sem_ver_str)
    assert values_tuple == (sv.major, sv.minor, sv.patch)


@pytest.mark.parametrize(
    "sem_ver_str", ("1,2,3", "0.0", "11 12 31", "notvalid", "1.2.3-pre.4")
)
def test_bad_sem_ver(sem_ver_str):
    """ We only allow major.minor.patch so test "valid" but bad. """
    with pytest.raises(argparse.ArgumentTypeError):
        _ = sem_ver(sem_ver_str)
