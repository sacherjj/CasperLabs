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
import shutil


def make_dirs(path):
    try:
        os.makedirs(path)
    except OSError as e:
        if e.errno != errno.EEXIST:
            raise


def download(url, directory):
    make_dirs(directory)
    urllib.request.urlretrieve(url, join(directory, basename(url)))


PROTO_DIR = 'proto'

os.chdir('casper_client')
try: shutil.rmtree(f'{PROTO_DIR}')
except FileNotFoundError: pass

make_dirs(f'{PROTO_DIR}')
Path(f'{PROTO_DIR}/__init__.py').touch()

# This makes SCALAPB_DIR during download
download("https://raw.githubusercontent.com/scalapb/ScalaPB/master/protobuf/scalapb/scalapb.proto",
         f"{PROTO_DIR}")

copyfile(join(dirname(grpc_tools.__file__), '_proto/google/protobuf/empty.proto'), f'{PROTO_DIR}/empty.proto')
copyfile(join(dirname(grpc_tools.__file__), '_proto/google/protobuf/descriptor.proto'), f'{PROTO_DIR}/descriptor.proto')

copyfile('../../../protobuf/io/casperlabs/casper/protocol/CasperMessage.proto', f'{PROTO_DIR}/CasperMessage.proto')
os.system("""perl -p -i -e 's,^import \"google/protobuf/,import ",' proto/*""")
os.system("""perl -p -i -e 's,^import \"scalapb/,import ",' proto/*""")

google_proto = join(dirname(grpc_tools.__file__), '_proto')

protoc.main((
    '',
    f'-I./{PROTO_DIR}',
    '-I' + google_proto,
    '-I.',
    '--python_out=.',
    '--grpc_python_out=.',
    f'{PROTO_DIR}/empty.proto',
))

protoc.main((
    '',
    f'-I./{PROTO_DIR}',
    '-I' + google_proto,
    '-I.',
    '--python_out=.',
    '--grpc_python_out=.',
    f'{PROTO_DIR}/descriptor.proto',
))

protoc.main((
    '',
    f'-I./{PROTO_DIR}',
    '-I' + google_proto,
    '-I.',
    '--python_out=.',
    '--grpc_python_out=.',
    f'{PROTO_DIR}/scalapb.proto',
))

protoc.main((
    '',
    '-I.',
    f'-I./{PROTO_DIR}',
    '-I' + google_proto,
    #'-I../../../../protobuf/io/casperlabs/casper/protocol/',
    '--python_out=.',
    '--grpc_python_out=.',
    f'{PROTO_DIR}/CasperMessage.proto',
))

os.system("mv *pb2*y proto/")
os.system("perl -p -i -e 's/from proto import /import /' proto/*y")
os.system("perl -p -i -e 's/import empty_pb2 as empty__pb2/from . import empty_pb2 as empty__pb2/' proto/*y")
os.system("perl -p -i -e 's/import scalapb_pb2 as scalapb__pb2/from . import scalapb_pb2 as scalapb__pb2/' proto/*y")
os.system("perl -p -i -e 's/import descriptor_pb2 as descriptor__pb2/from . import descriptor_pb2 as descriptor__pb2/' proto/*y")
os.system("perl -p -i -e 's/import CasperMessage_pb2 as proto_dot_CasperMessage__pb2/from . import CasperMessage_pb2 as proto_dot_CasperMessage__pb2/' proto/*y")
