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


THIS_DIRECTORY = os.path.dirname(os.path.realpath(__file__))

PROTOBUF_DIR = f'{THIS_DIRECTORY}/../../../protobuf'
PROTO_DIR = f'{THIS_DIRECTORY}/casper_client/proto'
PACKAGE_DIR = f'{THIS_DIRECTORY}/casper_client'


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


def patch_imports(file_names):
    print(f'Patch generated Python gRPC modules...: {file_names}')
    for file_name in file_names:
        with in_place.InPlace(file_name) as f:
            for line in f:
                words = line.split()
                if words and words[0] == 'import' and words[-1].endswith('_pb2'):
                    line = f'from . {line}'
                f.write(line)


def modify_files(pairs, files):
    for file_name in files:
        replace_in_place(pairs, file_name)


def run_protoc(file_names, PROTO_DIR = PROTO_DIR):
    print(f'Run protoc...: {file_names}')
    google_proto = join(dirname(grpc_tools.__file__), '_proto')
    for file_name in file_names:
        protoc.main((
            '',
            f'-I{PROTO_DIR}',
            '-I' + google_proto,
            f'--python_out={PACKAGE_DIR}',
            f'--grpc_python_out={PACKAGE_DIR}',
            file_name,
        ))



def collect_proto_files():
    print('Collect files...')

    download("https://raw.githubusercontent.com/scalapb/ScalaPB/master/protobuf/scalapb/scalapb.proto",
             f"{PROTO_DIR}")

    copyfile(join(dirname(grpc_tools.__file__), '_proto/google/protobuf/empty.proto'), f'{PROTO_DIR}/empty.proto')
    copyfile(join(dirname(grpc_tools.__file__), '_proto/google/protobuf/descriptor.proto'), f'{PROTO_DIR}/descriptor.proto')

    copyfile(f'{PROTOBUF_DIR}/io/casperlabs/casper/consensus/info.proto', f'{PROTO_DIR}/info.proto')
    copyfile(f'{PROTOBUF_DIR}/google/api/http.proto', f'{PROTO_DIR}/http.proto')
    copyfile(f'{PROTOBUF_DIR}/google/api/annotations.proto', f'{PROTO_DIR}/annotations.proto')
    copyfile(f'{PROTOBUF_DIR}/io/casperlabs/casper/protocol/CasperMessage.proto', f'{PROTO_DIR}/CasperMessage.proto')
    copyfile(f'{PROTOBUF_DIR}/io/casperlabs/casper/consensus/consensus.proto', f'{PROTO_DIR}/consensus.proto')
    copyfile(f'{PROTOBUF_DIR}/io/casperlabs/node/api/casper.proto', f'{PROTO_DIR}/casper.proto')
    copyfile(f'{PROTOBUF_DIR}/io/casperlabs/node/api/control.proto', f'{PROTO_DIR}/control.proto')


def patch_proto_files():
    print('Patch proto files...')
    modify_files([('import "google/protobuf/', 'import "'),
                  ('import "scalapb/', 'import "'),
                  ('import "io/casperlabs/casper/consensus/', 'import "'),
                  ('import "google/api/', 'import "')],
                 glob.glob(f'{PROTO_DIR}/*.proto'))

def clean_up():
    try: shutil.rmtree(f'{PROTO_DIR}')
    except FileNotFoundError: pass
    make_dirs(f'{PROTO_DIR}')

def run_codegen():
    clean_up()

    collect_proto_files()
    patch_proto_files()

    proto_files = glob.glob(f'{PROTO_DIR}/*.proto')
    run_protoc(proto_files)

    generated_files = glob.glob(f'{PACKAGE_DIR}/*pb2*py')
    patch_imports(generated_files)


if __name__ == '__main__':
    run_codegen()
