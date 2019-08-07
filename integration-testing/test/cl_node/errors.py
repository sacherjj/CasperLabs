from typing import Tuple, Union


class InterruptedException(Exception):
    pass


class CasperLabsNodeAddressNotFoundError(Exception):
    pass


class NonZeroExitCodeError(Exception):
    def __init__(
        self, command: Tuple[Union[int, str], ...], exit_code: int, output: str
    ):
        self.command = command
        self.exit_code = exit_code
        self.output = output

    def __repr__(self) -> str:
        return f"{self.__class__.__name__}({repr(self.command)}, {self.exit_code}, {repr(self.output)})"


class CommandTimeoutError(Exception):
    def __init__(self, command: Union[Tuple[str, ...], str], timeout: int) -> None:
        self.command = command
        self.timeout = timeout


class UnexpectedShowBlocksOutputFormatError(Exception):
    def __init__(self, output: str) -> None:
        self.output = output


class UnexpectedProposeOutputFormatError(Exception):
    def __init__(self, output: str) -> None:
        self.output = output
