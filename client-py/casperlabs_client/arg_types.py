import argparse
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
