import base64
from os.path import dirname, realpath
from typing import Union
from pathlib import Path

from casperlabs_client.utils import hexify


def read_file(file_path: Union[Path, str]) -> str:
    with open(file_path, "r") as f:
        return f.read()


def read_binary_file(file_path: Union[Path, str]) -> bytes:
    with open(file_path, "rb") as f:
        return f.read()


def write_file(file_path: Union[Path, str], text: str) -> None:
    with open(file_path, "w") as f:
        f.write(text)


def write_binary_file(file_path: Union[Path, str], data: bytes) -> None:
    with open(file_path, "wb") as f:
        f.write(data)


def read_pem_key(file_name: str):
    with open(file_name) as f:
        s = [line for line in f.readlines() if line and not line.startswith("-----")][
            0
        ].strip()
        r = base64.b64decode(s)
        return len(r) % 32 == 0 and r[:32] or r[-32:]


def _print_blocks(response, element_name="block"):
    count = 0
    for block in response:
        print(f"------------- {element_name} {count} ---------------")
        _print_block(block)
        print("-----------------------------------------------------\n")
        count += 1
    print("count:", count)


def _print_block(block):
    print(hexify(block))


def _read_version() -> str:
    version_path = Path(dirname(realpath(__file__))) / "VERSION"
    return read_file(version_path).strip()
