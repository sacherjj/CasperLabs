#!/usr/bin/env python3
import os
import sys
import re
import errno
import urllib.request
import shutil
from glob import glob
from pathlib import Path
from shutil import copyfile
from setuptools import setup, find_packages
from os.path import basename, dirname, realpath, join

from setuptools.command.develop import develop as DevelopCommand
from setuptools.command.install import install as InstallCommand
from distutils.spawn import find_executable


THIS_DIRECTORY = Path(dirname(realpath(__file__)))
PACKAGE_DIR = THIS_DIRECTORY / "casperlabs_client"

PROTOBUF_DIR = THIS_DIRECTORY.parent / "protobuf"
PROTO_DIR = PACKAGE_DIR / "proto"
PROTO_IO_DIR = PROTOBUF_DIR / "io"

CONTRACTS_DIR = THIS_DIRECTORY.parent / "client" / "src" / "main" / "resources"
CONTRACT_FILENAMES = ("bonding.wasm", "transfer_to_account_u512.wasm", "unbonding.wasm")
VERSION_FILE = PACKAGE_DIR / "VERSION"
README_FILE = THIS_DIRECTORY / "README.md"

NAME = "casperlabs_client"


def read_version() -> str:
    """ Pull in release version from file """
    with open(VERSION_FILE, encoding="utf-8") as f:
        return f.read().strip()


def read_long_description() -> str:
    """ Read in README.md file for description """
    with open(README_FILE, encoding="utf-8") as f:
        return f.read()


def proto_compiler_check():
    proto_c = find_executable("protoc")
    if proto_c is None:
        sys.stderr.write(
            "protoc is not installed. "
            "Please install Protocol Buffers binary package for your Operating System.\n"
        )
        sys.exit(-1)


def python_compiler_check():
    if sys.version < "3.6":
        sys.stderr.write(f"{NAME} is only supported on Python versions 3.6+.\n")
        sys.exit(-1)


def make_dirs(path):
    try:
        os.makedirs(path)
    except OSError as e:
        if e.errno != errno.EEXIST:
            raise


def clean_up():
    try:
        shutil.rmtree(f"{PROTO_DIR}")
    except FileNotFoundError:
        pass
    for file_name in glob(f"{PACKAGE_DIR}/*pb2*py"):
        os.remove(file_name)


def modify_files(description, pairs, files):
    """ Patching the bad Python files google generates """

    def replace_in_place(pairs, file_name):
        import in_place

        with in_place.InPlace(file_name) as f:
            for line in f:
                for r, s in pairs:
                    line = re.sub(r, s, line)
                f.write(line)

    print(description, ":", files)
    for file_name in files:
        replace_in_place(pairs, file_name)


def run_protoc(file_names, PROTO_DIR=PROTO_DIR):
    """ protobuf generation """
    import grpc_tools
    from grpc_tools import protoc

    print(f"Run protoc...: {file_names}")
    google_proto = join(dirname(grpc_tools.__file__), "_proto")
    for file_name in file_names:
        protoc.main(
            (
                "",
                f"-I{PROTO_DIR}",
                "-I" + google_proto,
                f"--python_out={PACKAGE_DIR}",
                f"--python_grpc_out={PACKAGE_DIR}",
                f"--grpc_python_out={PACKAGE_DIR}",
                file_name,
            )
        )


def collect_proto_files():
    """ copy and download required proto files """
    import grpc_tools

    def download(url, directory):
        make_dirs(directory)
        urllib.request.urlretrieve(url, join(directory, basename(url)))

    print("Collect files...")

    download(
        "https://raw.githubusercontent.com/scalapb/ScalaPB/master/protobuf/scalapb/scalapb.proto",
        f"{PROTO_DIR}",
    )

    for filename in ("empty.proto", "descriptor.proto", "wrappers.proto"):
        copyfile(
            join(dirname(grpc_tools.__file__), f"_proto/google/protobuf/{filename}"),
            PROTO_DIR / filename,
        )

    # TODO: Why are these not coming from local versions?
    download(
        "https://raw.githubusercontent.com/CasperLabs/CasperLabs/dev/protobuf/google/api/annotations.proto",
        PROTO_DIR,
    )
    download(
        "https://raw.githubusercontent.com/CasperLabs/CasperLabs/dev/protobuf/google/api/http.proto",
        PROTO_DIR,
    )

    for file_name in PROTO_IO_DIR.glob("**/*.proto"):
        copyfile(file_name, PROTO_DIR / basename(file_name))
    print("Finished collecting files...")


def run_codegen():
    python_compiler_check()
    proto_compiler_check()
    clean_up()
    make_dirs(PROTO_DIR)
    collect_proto_files()
    modify_files(
        "Patch proto files' imports", [(r'".+/', '"')], glob(PROTO_DIR / "*.proto")
    )
    run_protoc(glob(PROTO_DIR / "*.proto"))
    modify_files(
        "Patch generated Python gRPC modules",
        pairs=[(r"(import .*_pb2)", r"from . \1")],
        files=glob(f"{PACKAGE_DIR}/*pb2*py"),
    )
    modify_files(
        "Patch generated Python gRPC modules (for asyncio)",
        pairs=[(r"(import .*_pb2)", r"from . \1")],
        files=[fn for fn in glob(PACKAGE_DIR / "*_grpc[.]py") if "_pb2_" not in fn],
    )
    pattern = CONTRACTS_DIR / "*.wasm"
    bundled_contracts = list(glob(pattern))
    if len(bundled_contracts) == 0:
        raise Exception(
            f"Could not find wasm files that should be bundled with the client. {pattern}"
        )
    for filename in bundled_contracts:
        shutil.copy(filename, PACKAGE_DIR)


def prepare_sdist():
    bundled_contracts = [(f, CONTRACTS_DIR / f) for f in CONTRACT_FILENAMES]
    # TODO: Build contracts from ee make
    for file_name, file_path in bundled_contracts:
        if not os.path.exists(file_path):
            raise Exception(f"Contract file {file_path} does not exit")
        copyfile(file_path, PACKAGE_DIR / file_name)
        print(f"Copied contract {file_name}")
    run_codegen()


class CInstall(InstallCommand):
    def run(self):
        super().run()


class CDevelop(DevelopCommand):
    def run(self):
        run_codegen()
        super().run()


# TODO: Is there a cleaner way to do this rather than inlined?
if len(sys.argv) > 1 and sys.argv[1] in ("sdist", "develop"):
    prepare_sdist()

setup(
    name=NAME,
    # Version now defined in VERSION file in casperlabs_client directory.
    version=read_version(),
    packages=find_packages(exclude=["tests"]),
    setup_requires=[
        "protobuf==3.9.1",
        "grpcio-tools>=1.20",
        "in-place==0.4.0",
        "grpcio>=1.20",
    ],
    install_requires=[
        "protobuf==3.9.1",
        "grpcio>=1.20",
        "pyblake2==1.1.2",
        "ed25519==1.4",
        "cryptography==2.8",
        "pycryptodome==3.9.4",
    ],
    cmdclass={"install": CInstall, "develop": CDevelop},
    description="Python Client for interacting with a CasperLabs Node",
    long_description=read_long_description(),
    long_description_content_type="text/markdown",
    include_package_data=True,
    package_data={NAME: [PACKAGE_DIR / "*.wasm", VERSION_FILE]},
    keywords="casperlabs blockchain ethereum smart-contracts",
    author="CasperLabs LLC",
    author_email="testing@casperlabs.io",
    license="CasperLabs Open Source License (COSL)",
    zip_safe=False,
    classifiers=[
        "Development Status :: 3 - Alpha",
        "Programming Language :: Python :: 3.6",
        "Programming Language :: Python :: 3.7",
        "Programming Language :: Python :: 3 :: Only",
        "Operating System :: OS Independent",
        "Intended Audience :: Developers",
    ],
    python_requires=">=3.6.0",
    url="https://casperlabs.io/",
    project_urls={
        "Source": "https://github.com/CasperLabs/CasperLabs/tree/dev/integration-testing/client/CasperLabsClient",
        "Readme": "https://github.com/CasperLabs/CasperLabs/blob/dev/integration-testing/client/CasperLabsClient/README.md",
    },
    entry_points={
        "console_scripts": ["casperlabs_client = casperlabs_client.cli:main"]
    },
)
