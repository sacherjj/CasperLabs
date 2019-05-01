#!/usr/bin/env python3
"""Runs protoc with the gRPC plugin to generate messages and gRPC stubs."""

import grpc_tools
from grpc_tools import protoc
import urllib.request
import os, errno
from os.path import basename, dirname, join


def makedirs(path):
    try:
        os.makedirs(path)
    except OSError as e:
        if e.errno != errno.EEXIST:
            raise


def download(url, directory):
    makedirs(directory)
    urllib.request.urlretrieve(url, os.path.join(directory, basename(url)))


download("https://raw.githubusercontent.com/scalapb/ScalaPB/master/protobuf/scalapb/scalapb.proto",
         "scalapb")

google_proto = join(dirname(grpc_tools.__file__), '_proto')

protoc.main((
    '',
    '-I' + google_proto,
    '-I.',
    '--python_out=.',
    '--grpc_python_out=.',
    'scalapb/scalapb.proto',
))

protoc.main((
    '',
    '-I.',
    '-I' + google_proto,
    '-I.',
    '-I../../../protobuf/io/casperlabs/casper/protocol/',
    '--python_out=.',
    '--grpc_python_out=.',
    '../../../protobuf/io/casperlabs/casper/protocol/CasperMessage.proto',
))
