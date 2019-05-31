import logging
import re
import time
from typing import TYPE_CHECKING, List

import pytest
import typing_extensions

from .common import Network, WaitTimeoutError


from test.cl_node.errors import NonZeroExitCodeError


class PredicateProtocol(typing_extensions.Protocol):
    def __str__(self) -> str:
        ...

    def is_satisfied(self) -> bool:
        ...


class LogsContainMessage:
    def __init__(self, node: 'Node', message: str, times: int = 1) -> None:
        self.node = node
        self.message = message
        self.times = times

    def __str__(self) -> str:
        args = ', '.join(repr(a) for a in (self.node.name, self.message))
        return '<{}({})>'.format(self.__class__.__name__, args)

    def is_satisfied(self) -> bool:
        return self.node.logs().count(self.message) >= self.times


class LogsContainOneOf:
    def __init__(self, node: 'Node', messages: List[str]) -> None:
        self.node = node
        self.messages = messages

    def __str__(self) -> str:
        args = ', '.join(repr(a) for a in (self.node.name, str(self.messages)))
        return '<{}({})>'.format(self.__class__.__name__, args)

    def is_satisfied(self) -> bool:
        return any(m in self.node.logs() for m in self.messages)


class NodeStarted(LogsContainMessage):
    def __init__(self, node: 'Node', times: int) -> None:
        super().__init__(node, 'io.casperlabs.node.NodeRuntime - Listening for traffic on casperlabs', times)


class ApprovedBlockReceivedHandlerStateEntered(LogsContainOneOf):
    def __init__(self, node: 'Node') -> None:
        super().__init__(node, ['Making a transition to ApprovedBlockRecievedHandler state.',
                                'Making the transition to block processing.'])


class NewForkChoiceTipBlock(LogsContainMessage):
    def __init__(self, node: 'Node', block: str) -> None:
        super().__init__(node, f'New fork-choice tip is block {block}....')


class RegexBlockRequest:
    regex = None

    def __init__(self, node: 'Node', node_name: str) -> None:
        self.regex = re.compile(self.regex.format(node_name))
        self.node = node

    def is_satisfied(self) -> bool:
        match = self.regex.search(self.node.logs())
        return bool(match)


class ReceivedApprovedBlockRequest(RegexBlockRequest):
    regex = r"Received ApprovedBlockRequest from Node(.*,{},\d+,\d+)"


class StreamedPacketRequest(RegexBlockRequest):
    regex = r"Streamed packet .* to Node(.*,{},\d+,\d+)"


class SendingApprovedBlockRequest(RegexBlockRequest):
    regex = r"Sending ApprovedBlock to Node(.*,{},\d+,\d+)"


class ConnectedToOtherNode(RegexBlockRequest):
    regex = r"(Connected to casperlabs:)|(Listening for traffic on casperlabs:)"

    def __init__(self, node: 'Node', node_name: str, times: int) -> None:
        self.times = times
        super().__init__(node, node_name)

    def is_satisfied(self) -> bool:
        match = self.regex.findall(self.node.logs())
        return len(match) >= self.times


class ApprovedBlockReceived(LogsContainMessage):
    def __init__(self, node: 'Node') -> None:
        super().__init__(node, 'Valid ApprovedBlock received!')


class RequestedForkTip(LogsContainMessage):
    def __init__(self, node: 'Node', times: int) -> None:
        super().__init__(node, 'Requested fork tip from peers', times)


class WaitForGoodBye(LogsContainMessage):
    def __init__(self, node: 'Node') -> None:
        super().__init__(node, 'Goodbye.')


class MetricsAvailable:
    def __init__(self, node: 'Node', number_of_blocks: int) -> None:
        self.node = node
        self.number_of_blocks = number_of_blocks

    def is_satisfied(self) -> bool:
        _, data = self.node.get_metrics()
        received_blocks_pattern = re.compile(r"^casperlabs_casper_packet_handler_blocks_received_total ([1-9][0-9]*).0\s*$", re.MULTILINE | re.DOTALL)
        blocks = received_blocks_pattern.search(data)
        if blocks is None:
            return False
        return int(blocks.group(1)) == self.number_of_blocks


class TotalBlocksOnNode:
    def __init__(self, node: 'Node', number_of_blocks: int) -> None:
        self.node = node
        self.number_of_blocks = number_of_blocks

    def is_satisfied(self) -> bool:
        _, data = self.node.get_metrics()
        received_blocks_pattern = re.compile(r"^casperlabs_casper_packet_handler_blocks_received_total (\d+).0\s*$", re.MULTILINE | re.DOTALL)
        duplicates_blocks_pattern = re.compile(r"^casperlabs_casper_packet_handler_blocks_received_again_total (\d+).0\s*$", re.MULTILINE | re.DOTALL)
        api_created_blocks_pattern = re.compile(r"^casperlabs_casper_block_api_create_blocks_total (\d+).0\s*$", re.MULTILINE | re.DOTALL)
        total_blocks = received_blocks_pattern.search(data)
        dup_blocks = duplicates_blocks_pattern.search(data)
        api_blocks = api_created_blocks_pattern.search(data)
        if None in [total_blocks, dup_blocks, api_blocks]:
            return False
        count = int(total_blocks.group(1)) - int(dup_blocks.group(1) or 0) + int(api_blocks.group(1) or 0)
        logging.info(count)
        return count == self.number_of_blocks


class HasAtLeastPeers:
    def __init__(self, node: 'Node', minimum_peers_number: int) -> None:
        self.node = node
        self.minimum_peers_number = minimum_peers_number
        self.metric_regex = re.compile(r"^casperlabs_comm_rp_connect_peers (\d+).0\s*$", re.MULTILINE | re.DOTALL)
        self.new_metric_regex = re.compile(r"^casperlabs_comm_discovery_kademlia_peers (\d+).0\s*$",
                                           re.MULTILINE | re.DOTALL)

    def __str__(self) -> str:
        args = ', '.join(repr(a) for a in (self.node.name, self.minimum_peers_number))
        return '<{}({})>'.format(self.__class__.__name__, args)

    def is_satisfied(self) -> bool:
        output = self.node.get_metrics_strict()
        match = self.metric_regex.search(output)
        if match is None:
            match = self.new_metric_regex.search(output)
            if match is None:
                return False
        peers = int(match[1])
        return peers >= self.minimum_peers_number


class BlockContainsString:
    def __init__(self, node: 'Node', block_hash: str, expected_string: str) -> None:
        self.node = node
        self.block_hash = block_hash
        self.expected_string = expected_string

    def __str__(self) -> str:
        args = ', '.join(repr(a) for a in (self.node.name, self.block_hash, self.expected_string))
        return '<{}({})>'.format(self.__class__.__name__, args)

    def is_satisfied(self) -> bool:
        block = self.node.get_block(self.block_hash)
        return self.expected_string in block


class LastFinalisedHash(LogsContainMessage):
    def __init__(self, node: 'Node', hash_string: str) -> None:
        super().__init__(node, f'New last finalized block hash is {hash_string}')


class BlocksCountAtLeast:
    def __init__(self, node: 'Node', blocks_count: int, max_retrieved_blocks: int) -> None:
        self.node = node
        self.blocks_count = blocks_count
        self.max_retrieved_blocks = max_retrieved_blocks

    def __str__(self) -> str:
        args = ', '.join(repr(a) for a in (self.node.name, self.blocks_count, self.max_retrieved_blocks))
        return '<{}({})>'.format(self.__class__.__name__, args)

    def is_satisfied(self) -> bool:
        actual_blocks_count = self.node.client.get_blocks_count(self.max_retrieved_blocks)
        logging.info("THE ACTUAL BLOCKS COUNT: {}".format(actual_blocks_count))
        return actual_blocks_count >= self.blocks_count


def wait_on_using_wall_clock_time(predicate: PredicateProtocol, timeout_seconds: int) -> None:
    logging.info("AWAITING {}".format(predicate))

    elapsed = 0
    while elapsed < timeout_seconds:
        start_time = time.time()

        is_satisfied = predicate.is_satisfied()
        if is_satisfied:
            logging.info("SATISFIED {}".format(predicate))
            return

        condition_evaluation_duration = time.time() - start_time
        elapsed = int(elapsed + condition_evaluation_duration)
        time_left = timeout_seconds - elapsed

        # iteration duration is 15% of remaining timeout
        # but no more than 10s and no less than 1s
        iteration_duration = int(min(10, max(1, int(0.15 * time_left))))

        time.sleep(iteration_duration)
        elapsed = elapsed + iteration_duration

    pytest.fail('Failed to satisfy {} after {}s'.format(predicate, elapsed))


def wait_for_block_contains(node: 'Node', block_hash: str, expected_string: str, timeout_seconds: int):
    predicate = BlockContainsString(node, block_hash, expected_string)
    wait_on_using_wall_clock_time(predicate, timeout_seconds)


def wait_for_finalised_hash(node: 'Node', hash_string: str, timeout_seconds: int):
    predicate = LastFinalisedHash(node, hash_string)
    wait_on_using_wall_clock_time(predicate, timeout_seconds)


def wait_for_new_fork_choice_tip_block(node: 'Node', block: str, timeout_seconds: int):
    predicate = NewForkChoiceTipBlock(node, block)
    wait_on_using_wall_clock_time(predicate, timeout_seconds)


def wait_for_blocks_count_at_least(node: 'Node', expected_blocks_count: int, max_retrieved_blocks: int, timeout_seconds: int = 10):
    predicate = BlocksCountAtLeast(node, expected_blocks_count, max_retrieved_blocks)
    wait_using_wall_clock_time_or_fail(predicate, timeout_seconds)


def wait_for_node_started(node: 'Node', startup_timeout: int, times: int = 1):
    predicate = NodeStarted(node, times)
    wait_on_using_wall_clock_time(predicate, startup_timeout)


def wait_for_approved_block_received_handler_state(node: 'Node', timeout_seconds: int):
    predicate = ApprovedBlockReceivedHandlerStateEntered(node)
    wait_on_using_wall_clock_time(predicate, timeout_seconds)


def wait_for_requested_for_fork_tip(node: 'Node', timeout_seconds: int, times: int = 1):
    predicate = RequestedForkTip(node, times)
    wait_on_using_wall_clock_time(predicate, timeout_seconds)


def wait_for_good_bye(node: 'Node', timeout_seconds: int):
    predicate = WaitForGoodBye(node)
    wait_on_using_wall_clock_time(predicate, timeout_seconds)


def wait_for_received_approved_block_request(node: 'Node', node_name: str, timeout_seconds: int):
    predicate = ReceivedApprovedBlockRequest(node, node_name)
    wait_on_using_wall_clock_time(predicate, timeout_seconds)


def wait_for_sending_approved_block_request(node: 'Node', node_name: str, timeout_seconds: int):
    predicate = SendingApprovedBlockRequest(node, node_name)
    wait_on_using_wall_clock_time(predicate, timeout_seconds)


def wait_for_streamed_packet(node: 'Node', node_name: str, timeout_seconds: int):
    predicate = StreamedPacketRequest(node, node_name)
    wait_on_using_wall_clock_time(predicate, timeout_seconds)


def wait_for_peers_count_at_least(node: 'Node', npeers: int, timeout_seconds: int) -> None:
    predicate = HasAtLeastPeers(node, npeers)
    wait_using_wall_clock_time_or_fail(predicate, timeout_seconds)


def wait_for_metrics_and_assert_blocks_avaialable(node: 'Node', timeout_seconds: int, number_of_blocks: int) -> None:
    predicate = MetricsAvailable(node, number_of_blocks)
    wait_using_wall_clock_time_or_fail(predicate, timeout_seconds)


def wait_for_count_the_blocks_on_node(node: 'Node', timeout_seconds: int = 10, number_of_blocks: int = 1) -> None:
    predicate = TotalBlocksOnNode(node, number_of_blocks)
    wait_using_wall_clock_time_or_fail(predicate, timeout_seconds)


def wait_using_wall_clock_time_or_fail(predicate: PredicateProtocol, timeout: int) -> None:
    while True:
        try:
            wait_using_wall_clock_time(predicate, timeout)
            return
        except WaitTimeoutError:
            pytest.fail('Failed to satisfy {} after {}s'.format(predicate, timeout))
        except NonZeroExitCodeError:
            logging.info("not ready")


def wait_using_wall_clock_time(predicate: PredicateProtocol, timeout: int) -> None:
    logging.info("AWAITING {}".format(predicate))

    elapsed = 0
    while elapsed < timeout:
        start_time = time.time()

        is_satisfied = predicate.is_satisfied()
        if is_satisfied:
            logging.info("SATISFIED {}".format(predicate))
            return

        condition_evaluation_duration = time.time() - start_time
        elapsed = int(elapsed + condition_evaluation_duration)
        time_left = timeout - elapsed

        # iteration duration is 15% of remaining timeout
        # but no more than 10s and no less than 1s
        iteration_duration = int(min(10, max(1, int(0.15 * time_left))))

        time.sleep(iteration_duration)
        elapsed = elapsed + iteration_duration
    logging.info("TIMEOUT %s", predicate)
    raise WaitTimeoutError(predicate, timeout)


def wait_for_approved_block_received(network: 'Network', timeout: int) -> None:
    for peer in network.peers:
        predicate = ApprovedBlockReceived(peer)
        wait_on_using_wall_clock_time(predicate, timeout)


def wait_for_connected_to_node(node: 'Node', other_node_name: str, timeout: int, times: int = 1) -> None:
    predicate = ConnectedToOtherNode(node, other_node_name, times)
    wait_on_using_wall_clock_time(predicate, timeout)


def wait_for_started_network(node_startup_timeout: int, network: 'Network'):
    for peer in network.peers:
        wait_for_node_started(peer, node_startup_timeout)


def wait_for_converged_network(timeout: int, network: 'Network', peer_connections: int):
    bootstrap_predicate = HasAtLeastPeers(network.bootstrap, len(network.peers))
    wait_on_using_wall_clock_time(bootstrap_predicate, timeout)

    for peer in network.peers:
        peer_predicate = HasAtLeastPeers(peer, peer_connections)
        wait_on_using_wall_clock_time(peer_predicate, timeout)
