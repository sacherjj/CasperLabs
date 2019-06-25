#!/usr/bin/env python3

from setuptools import setup

with open("README.md", "r") as fh:
    long_description = fh.read()

setup(
    name='casperlabs_client',
    version='0.3.0',
    packages=['casper_client', 'casper_client.proto'],
    install_requires=['grpcio>=1.20'],
    description='Python Client for interacting with a CasperLabs Node',
    long_description=long_description,
    long_description_content_type='text/markdown',
    author='CasperLabs',
    license='CasperLabs Open Source License (COSL)',
    classifiers=[
        'Development Status :: 3 - Alpha',
        'Intended Audience :: Developers',
        'Programming Language :: Python 3.6',
        'Programming Language :: Python 3.7',
        'Programming Language:: Python:: 3:: Only',
        'Operating System :: OS Independent',
    ],
    python_requires='>=3.6.0',
    project_urls={
        'Source': 'https://github.com/CasperLabs/CasperLabs/tree/dev/client/python'
    },
)
