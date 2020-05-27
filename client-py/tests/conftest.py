from pathlib import Path

import pytest
import tempfile
from casperlabs_client import CasperLabsClient


@pytest.fixture(scope="session")
def account_keys_directory():
    with tempfile.TemporaryDirectory() as directory:
        CasperLabsClient.keygen(directory)
        yield Path(directory)


@pytest.fixture(scope="session")
def validator_keys_directory():
    with tempfile.TemporaryDirectory() as directory:
        CasperLabsClient.validator_keygen(directory)
        yield Path(directory)
