#!/usr/bin/env python3

from setuptools import setup
from os import path

here = path.abspath(path.dirname(__file__))

with open(path.join(here, "README.md"),  encoding="utf-8") as fh:
    long_description = fh.read()

setup(
    name='casperlabs_client',
    version='0.3.1',
    packages=['casper_client', 'casper_client.proto'],
    install_requires=['grpcio>=1.20'],
    description='Python Client for interacting with a CasperLabs Node',
    long_description=long_description,
    long_description_content_type='text/markdown',
    include_package_data=True,
    keywords='casperlabs blockchain ethereum smart-contracts',
    author='CasperLabs LLC',
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
