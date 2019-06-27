#!/usr/bin/env python3
import os
import re
import errno
import urllib.request
import shutil
from glob import glob
from pathlib import Path
from shutil import copyfile
from setuptools import setup
from os import path
from os.path import basename, dirname, join

from setuptools.command.install import install as InstallCommand
from setuptools.command.test import test as TestCommand

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
    import in_place
    with in_place.InPlace(file_name) as f:
        for line in f:
            for r, s in pairs:
                line = re.sub(r, s, line)
            f.write(line)


def modify_files(description, pairs, files):
    print(description, ':', files)
    for file_name in files:
        replace_in_place(pairs, file_name)


def run_protoc(file_names, PROTO_DIR=PROTO_DIR):
    import grpc_tools
    from grpc_tools import protoc
    print(f'Run protoc...: {file_names}')
    google_proto = join(dirname(grpc_tools.__file__), '_proto')
    for file_name in file_names:
        protoc.main(('',
                     f'-I{PROTO_DIR}',
                     '-I' + google_proto,
                     f'--python_out={PACKAGE_DIR}',
                     f'--grpc_python_out={PACKAGE_DIR}',
                     file_name,
                     ))


def collect_proto_files():
    import grpc_tools
    print('Collect files...')

    download("https://raw.githubusercontent.com/scalapb/ScalaPB/master/protobuf/scalapb/scalapb.proto",
             f"{PROTO_DIR}")

    copyfile(join(dirname(grpc_tools.__file__), '_proto/google/protobuf/empty.proto'), f'{PROTO_DIR}/empty.proto')
    copyfile(join(dirname(grpc_tools.__file__), '_proto/google/protobuf/descriptor.proto'),
             f'{PROTO_DIR}/descriptor.proto')
    copyfile(join(dirname(grpc_tools.__file__), '_proto/google/protobuf/wrappers.proto'), f'{PROTO_DIR}/wrappers.proto')

    copyfile(f'{PROTOBUF_DIR}/google/api/http.proto', f'{PROTO_DIR}/http.proto')
    copyfile(f'{PROTOBUF_DIR}/google/api/annotations.proto', f'{PROTO_DIR}/annotations.proto')

    for file_name in Path(f"{PROTOBUF_DIR}/io/").glob('**/*.proto'):
        copyfile(file_name, f'{PROTO_DIR}/{basename(file_name)}')


def clean_up():
    try:
        shutil.rmtree(f'{PROTO_DIR}')
    except FileNotFoundError:
        pass
    make_dirs(f'{PROTO_DIR}')

    for file_name in glob(f'{PACKAGE_DIR}/*pb2*py'):
        os.remove(file_name)


def run_codegen():
    clean_up()

    collect_proto_files()

    modify_files("Patch proto files' imports", [(r'".+/', '"')], glob(f'{PROTO_DIR}/*.proto'))

    run_protoc(glob(f'{PROTO_DIR}/*.proto'))

    modify_files('Patch generated Python gRPC modules',
                 [(r'(import .*_pb2)', r'from . \1')],
                 glob(f'{PACKAGE_DIR}/*pb2*py'))


here = path.abspath(path.dirname(__file__))

with open(path.join(here, "README.md"),  encoding="utf-8") as fh:
    long_description = fh.read()


class CInstall(InstallCommand):
    def run(self):
        run_codegen()
        super(CInstall, self).run()

class CTest(TestCommand):
    def run(self):
        run_codegen()
        super(CTest, self).run()
setup(
    name='casperlabs_client',
    version='0.3.1',
    packages=['casper_client', 'casper_client.proto'],
    tests_require=['pytest', 'in-place', 'pytest-runner', 'grpcio-tools>=1.20'],
    setup_requires=['pytest-runner', 'grpcio-tools>=1.20', 'in-place'],
    install_requires=['grpcio>=1.20', 'pyblake2==1.1.2', 'ed25519==1.4'],
    cmdclass={
        'install': CInstall,
        'test': CTest
    },
    description='Python Client for interacting with a CasperLabs Node',
    long_description=long_description,
    long_description_content_type='text/markdown',
    include_package_data=True,
    keywords='casperlabs blockchain ethereum smart-contracts',
    author='CasperLabs LLC',
    author_email='testing@casperlabs.io',
    license='CasperLabs Open Source License (COSL)',
    classifiers=[
        'Development Status :: 3 - Alpha',
        'Programming Language :: Python :: 3.6',
        'Programming Language :: Python :: 3.7',
        'Programming Language :: Python :: 3 :: Only',
        'Operating System :: OS Independent',
        'Intended Audience :: Developers',
    ],
    python_requires='>=3.6.0',
    project_urls={
        'Source': 'https://github.com/CasperLabs/CasperLabs/tree/dev/integration-testing/client/CasperClient'
    },
)
