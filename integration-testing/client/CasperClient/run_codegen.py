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
import in_place
import glob

PROTO_DIR = 'proto'

def make_dirs(path):
    try:
        os.makedirs(path)
    except OSError as e:
        if e.errno != errno.EEXIST:
            raise


def download(url, directory):
    make_dirs(directory)
    urllib.request.urlretrieve(url, join(directory, basename(url)))


def replace_in_place(pairs, file_name):
    with in_place.InPlace(file_name) as f:
        for line in f:
            for s, r in pairs:
                line = line.replace(s, r)
            f.write(line)


def modify_files(pairs, files):
    for file_name in files:
        replace_in_place(pairs, file_name)


def run_protoc(*file_names, PROTO_DIR = PROTO_DIR):
    google_proto = join(dirname(grpc_tools.__file__), '_proto')
    for file_name in file_names:
        protoc.main((
            '',
            f'-I./{PROTO_DIR}',
            '-I' + google_proto,
            '-I.',
            '--python_out=.',
            '--grpc_python_out=.',
            file_name,
        ))

os.chdir('casper_client')
try: shutil.rmtree(f'{PROTO_DIR}')
except FileNotFoundError: pass

make_dirs(f'{PROTO_DIR}')
Path(f'{PROTO_DIR}/__init__.py').touch()

download("https://raw.githubusercontent.com/scalapb/ScalaPB/master/protobuf/scalapb/scalapb.proto",
         f"{PROTO_DIR}")

copyfile(join(dirname(grpc_tools.__file__), '_proto/google/protobuf/empty.proto'), f'{PROTO_DIR}/empty.proto')
copyfile(join(dirname(grpc_tools.__file__), '_proto/google/protobuf/descriptor.proto'), f'{PROTO_DIR}/descriptor.proto')

copyfile('../../../../protobuf/google/api/http.proto', f'{PROTO_DIR}/http.proto')
copyfile('../../../../protobuf/google/api/annotations.proto', f'{PROTO_DIR}/annotations.proto')
copyfile('../../../../protobuf/io/casperlabs/casper/protocol/CasperMessage.proto', f'{PROTO_DIR}/CasperMessage.proto')

print('Patch proto files...')
modify_files([('import "google/protobuf/', 'import "'),
              ('import "scalapb/', 'import "'),
              ('import "google/api/', 'import "')],
             glob.glob('proto/*'))


print('Run protoc...')
run_protoc(f'{PROTO_DIR}/empty.proto',
           f'{PROTO_DIR}/descriptor.proto',
           f'{PROTO_DIR}/scalapb.proto',
           f'{PROTO_DIR}/CasperMessage.proto')

print('Patch generated Python gRPC modules...')
modify_files([('from proto import ', 'import '),
              ('import empty_pb2 as empty__pb2', 'from . import empty_pb2 as empty__pb2'),
              ('import scalapb_pb2 as scalapb__pb2', 'from . import scalapb_pb2 as scalapb__pb2'),
              ('import descriptor_pb2 as descriptor__pb2', 'from . import descriptor_pb2 as descriptor__pb2'),
              ('import CasperMessage_pb2 as proto_dot_CasperMessage__pb2', 'from . import CasperMessage_pb2 as proto_dot_CasperMessage__pb2')],
            glob.glob('proto/*py'))

