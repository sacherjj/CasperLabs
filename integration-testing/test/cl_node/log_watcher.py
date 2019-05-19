import threading
import contextlib
import time
import logging

from typing import Any


class LogWatcherThread(threading.Thread):
    """
    This class will hook onto a container log and parse lines from the generator until a condition is met.

    Replaces full log parsing and searching with wait methods.
    """

    def __init__(self, container: "Container") -> None:
        super().__init__()
        self.located_event = threading.Event()
        self.stop_event = threading.Event()
        self.container = container
        self.data = None

    def __repr__(self):
        return f'<{self.__class__.__name__}({self.container.name})'

    def run(self) -> None:
        end_line = self.container.logs(tail=1)
        containers_log_lines_generator = self.container.logs(stream=True, follow=True)
        # Fast Forward to end, so we don't get previous false positives
        lines_count = 0
        while end_line != next(containers_log_lines_generator):
            lines_count += 1
        logging.info(f'Fast Forward Complete with {lines_count} lines skipped.  End Line: {end_line}')
        try:
            while not self.stop_event.is_set() and not self.located_event.is_set():
                line = next(containers_log_lines_generator).decode('utf-8').rstrip()
                if self.condition_met(line):
                    self.located_event.set()
        except StopIteration:
            pass

    def condition_met(self, line: str) -> bool:
        """
        Detect if line given meets wait condition.
        Set self.data with any return data needed.

        :param line: Line of log text to evaluate
        :return: boolean
        """
        return False


class TextInLogLine(LogWatcherThread):
    search_text = None

    def __init__(self, container: "Container") -> None:
        super().__init__(container)

    def condition_met(self, line: str) -> bool:
        if self.search_text in line:
            self.data = line
            return True
        return False


class GoodbyeInLogLine(TextInLogLine):
    search_text = 'Goodbye.'


class RequestedForkTipFromPeersInLogLine(TextInLogLine):
    search_text = 'Requested fork tip from peers'


@contextlib.contextmanager
def wait_for_log_watcher(log_watcher: "LogWatcherThread", timeout_secs: float = 180) -> Any:
    """
    Log watcher does not parse the entire log, so a context manager starts up detection prior to
    the command that will cause it.  This can allow detection to be complete prior to coming back from
    yield, which would have failed if run after.

    If no data is required, construction of LogWatcherThread object can be in the with:

        with wait_for_log_watcher(GoodbyeInLogLine(cl_node_0.node)):
            cl_node_0.stop()

    If you wish to retrieve object.data, you must create the LogWatcherThread object prior to using
    the context manager:

        text_in_log = GoodbyeInLogLine(cl_node_0.node)
        with wait_for_log_Watcher(text_in_log):
           cl_node_0.stop
        data_returned = text_in_log.data

    :param log_watcher: A LogWatcherThread object
    :param timeout_secs: number of seconds to allow for detection prior to raising error
    """
    located_event = log_watcher.located_event
    stop_event = log_watcher.stop_event
    stop_time = time.time() + timeout_secs
    logging.info(f'AWAITING {log_watcher.__repr__()} for {timeout_secs} sec(s)')
    log_watcher.start()

    yield

    while True:
        if located_event.is_set():
            log_watcher.join()
            logging.info(f'SATISFIED {log_watcher.__repr__()} with data: {log_watcher.data}')
            return
        if time.time() > stop_time:

            # While debugging issues with Log Watcher, doing final check
            logging.info(f'LOG_WATCHER_ERROR_STATE_RECHECK')
            c_logs = log_watcher.container.logs(tail=10).decode('utf-8').split('\n')
            search_text = log_watcher.__class__.search_text
            for line in c_logs:
                if search_text in line:
                    logging.info(f'RETRY OF {log_watcher.__class__.__name__} Successful.')
                    stop_event.set()
                    return

            # Setting stop event will tear down thread after next log line received or container shutdown
            stop_event.set()
            raise Exception(f'{log_watcher.container.name} did not satisfy {log_watcher.__repr__()} '
                            f'after {timeout_secs} sec(s).')
        time.sleep(0.1)


