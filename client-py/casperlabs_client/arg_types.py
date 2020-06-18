import argparse


def positive_integer(number):
    """Check number is an integer greater than 0"""
    n = int(number)
    if n < 1:
        raise argparse.ArgumentTypeError(f"{number} is not a positive int value")
    return n
