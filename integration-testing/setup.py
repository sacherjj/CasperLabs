#!/usr/bin/env python3

import os
from setuptools import setup, find_packages

THIS_DIRECTORY = os.path.dirname(os.path.realpath(__file__))

NAME = "casperlabs_local_net"

with open(os.path.join(THIS_DIRECTORY, "README.md"), encoding="utf-8") as fh:
    long_description = fh.read()

setup(
    name=NAME,
    version="0.1.0",
    packages=find_packages(exclude=["test", "client", "node_modules", "util"]),
    setup_requires=[],
    install_requires=["casperlabs-client", "docker"],
    cmdclass={},
    description="Python package for CasperLabs integration testing and node configuration for local development",
    long_description=long_description,
    long_description_content_type="text/markdown",
    include_package_data=True,
    package_data={
        NAME: [
            f"{THIS_DIRECTORY}/resources/*",
            f"{THIS_DIRECTORY}/resources/*/*",
            f"{THIS_DIRECTORY}/resources/*/*/*",
            f"{THIS_DIRECTORY}/resources/*/*/*/*",
        ]
    },
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
        "Source": "https://github.com/CasperLabs/CasperLabs/tree/dev/integration-testing/casperlabs_local_net",
        "Readme": "https://github.com/CasperLabs/CasperLabs/blob/dev/integration-testing/casperlabs_local_net/README.md",
    },
    entry_points={
        "console_scripts": [
            "casperlabs_local_node = casperlabs_local_net.casperlabs_local_node:main"
        ]
    },
)
