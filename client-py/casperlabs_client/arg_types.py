import argparse
import os
from pathlib import Path

import semver


def natural(number):
    """Check number is an integer greater than 0"""
    n = int(number)
    if n < 1:
        raise argparse.ArgumentTypeError(f"{number} is not a positive int value")
    return n


def sem_ver(sem_ver_str):
    """ Check string is a valid Sym Ver """
    error_str = f"{sem_ver_str} is not a valid format. Should be `major.minor.patch`"
    try:
        version = semver.VersionInfo.parse(sem_ver_str)
    except ValueError:
        raise argparse.ArgumentTypeError(error_str)
    # We are not using prerelease or build numbers, so should be None
    if version.prerelease is not None or version.build is not None:
        raise argparse.ArgumentTypeError(error_str)
    return version


def directory_for_write(path):
    """  """
    if not os.path.exists(path):
        raise argparse.ArgumentTypeError(f"Directory '{path}' does not exist")
    if not os.path.isdir(path):
        raise argparse.ArgumentTypeError(f"'{path}' is not a directory")
    if not os.access(path, os.W_OK):
        raise argparse.ArgumentTypeError(f"'{path}' does not have writing permissions")
    return Path(path)
