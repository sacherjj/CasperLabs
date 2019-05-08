#!/usr/bin/env python3
"""Runs protoc with the gRPC plugin to generate messages and gRPC stubs."""

import grpc_tools
from grpc_tools import protoc
import urllib.request
import os
import errno
from os.path import basename, dirname, join
from shutil import copyfile
from pathlib import Path


def make_dirs(path):
    try:
        os.makedirs(path)
    except OSError as e:
        if e.errno != errno.EEXIST:
            raise


def download(url, directory):
    make_dirs(directory)
    urllib.request.urlretrieve(url, join(directory, basename(url)))


os.chdir('casper_client')

PROTOS_DIR = 'protos'
SCALAPB_DIR = 'scalapb'
CASPER_MESSAGE_DIR = 'casper_message'

if not os.path.exists(PROTOS_DIR):
    os.mkdir(PROTOS_DIR)
# if not os.path.exists(f'{PROTOS_DIR}/{CASPER_MESSAGE_DIR}'):
#     os.mkdir(f'{PROTOS_DIR}/{CASPER_MESSAGE_DIR}')

# This makes SCALAPB_DIR during download
download("https://raw.githubusercontent.com/scalapb/ScalaPB/master/protobuf/scalapb/scalapb.proto",
         f"{PROTOS_DIR}/{SCALAPB_DIR}")

# Make all python packages for imports
Path(f'{PROTOS_DIR}/__init__.py').touch()
Path(f'{PROTOS_DIR}/{SCALAPB_DIR}/__init__.py').touch()
# Path(f'{PROTOS_DIR}/{CASPER_MESSAGE_DIR}/__init__.py').touch()


google_proto = join(dirname(grpc_tools.__file__), '_proto')

protoc.main((
    '',
    '-I' + google_proto,
    '-I.',
    '--python_out=.',
    '--grpc_python_out=.',
    f'{PROTOS_DIR}/{SCALAPB_DIR}/scalapb.proto',
))

copyfile('../../../../protobuf/io/casperlabs/casper/protocol/CasperMessage.proto',
         f'{PROTOS_DIR}/CasperMessage.proto')

protoc.main((
    '',
    '-I.',
    f'-I./{PROTOS_DIR}',
    '-I' + google_proto,
    '-I../../../../protobuf/io/casperlabs/casper/protocol/',
    '--python_out=.',
    '--grpc_python_out=.',
    f'{PROTOS_DIR}/CasperMessage.proto',
))

