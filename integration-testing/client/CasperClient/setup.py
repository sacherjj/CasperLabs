#!/usr/bin/env python3

from setuptools import setup

setup(
    name='casperlabs_client',
    version='0.3.0',
    packages=['casper_client', 'casper_client.proto'],
    install_requires=['grpcio>=1.20'],
    description='Python Client for interacting with a CasperLabs Node',
    author='CasperLabs',
    classifiers=[
        'Development Status :: 3 - Alpha',
        'Intended Authors :: Developers',
        'Programming Language :: Python 3.6',
    ],
    python_requires='>=3.6.0',
    project_urls={
        'Source': 'https://github.com/CasperLabs/CasperLabs/tree/dev/client/python'
    },
)
