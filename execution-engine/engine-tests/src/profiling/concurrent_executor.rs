//! This executable is designed to send a series of contracts in parallel to a running instance of
//! `casperlabs-engine-grpc-server` in order to gauge the server's performance.
//!
//! For details of how to run this executable, see the README in this directory or at
//! https://github.com/CasperLabs/CasperLabs/blob/dev/execution-engine/engine-tests/src/profiling/README.md#concurrent-executor

#[macro_use]
extern crate clap;
extern crate crossbeam_channel;
extern crate env_logger;
extern crate futures;
extern crate futures_cpupool;
extern crate grpc;
#[macro_use]
extern crate log;

extern crate casperlabs_engine_tests as engine_tests;
extern crate contract_ffi;
extern crate engine_core;
extern crate engine_grpc_server;
extern crate engine_shared;

use std::env;
use std::iter::Sum;
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use clap::{App, Arg};
use crossbeam_channel::{Receiver, Sender};
use futures::future::Future;
use futures::{stream, Stream};
use futures_cpupool::{CpuFuture, CpuPool};
use grpc::{ClientStubExt, RequestOptions};

use contract_ffi::base16;
use contract_ffi::value::U512;
use engine_grpc_server::engine_server::ipc::{ExecuteRequest, ExecuteResponse};
use engine_grpc_server::engine_server::ipc_grpc::{
    ExecutionEngineService, ExecutionEngineServiceClient,
};
use engine_tests::support::profiling_common;
use engine_tests::support::test_support::ExecuteRequestBuilder;

const APP_NAME: &str = "Concurrent Executor";
const ABOUT: &str =
    "A client which constructs several 'ExecuteRequest's and sends them concurrently to the \
     Execution Engine server.\n\nFirst run the 'state-initializer' executable to set up the \
     required global state, specifying a data dir.  This outputs the pre-state hash required as an \
     input to 'concurrent-executor'.  Then run the 'casperlabs-engine-grpc-server' specifying the \
     same data dir and a socket file.  Finally, 'concurrent-executor' can be run using the same \
     socket file.\n\nTo enable logging, set the env var 'RUST_LOG=concurrent_executor=info'.";

const SOCKET_ARG_NAME: &str = "socket";
const SOCKET_ARG_SHORT: &str = "s";
const SOCKET_ARG_VALUE_NAME: &str = "SOCKET";
const SOCKET_ARG_HELP: &str = "Path to Execution Engine server's socket file";

const PRE_STATE_HASH_ARG_NAME: &str = "pre-state-hash";
const PRE_STATE_HASH_ARG_SHORT: &str = "p";
const PRE_STATE_HASH_ARG_VALUE_NAME: &str = "HEX-ENCODED HASH";
const PRE_STATE_HASH_ARG_HELP: &str =
    "Pre-state hash; the output of running the 'state-initializer' executable";

const THREAD_COUNT_ARG_NAME: &str = "threads";
const THREAD_COUNT_ARG_SHORT: &str = "t";
const THREAD_COUNT_ARG_DEFAULT: &str = "8";
const THREAD_COUNT_ARG_VALUE_NAME: &str = "NUM";
const THREAD_COUNT_ARG_HELP: &str = "Worker thread count";

const REQUEST_COUNT_ARG_NAME: &str = "requests";
const REQUEST_COUNT_ARG_SHORT: &str = "r";
const REQUEST_COUNT_ARG_DEFAULT: &str = "100";
const REQUEST_COUNT_ARG_VALUE_NAME: &str = "NUM";
const REQUEST_COUNT_ARG_HELP: &str = "Total number of 'ExecuteRequest's to send";

const CONTRACT_NAME: &str = "transfer_to_existing_account.wasm";
const THREAD_PREFIX: &str = "client-worker-";
/// The maximum number of work objects which can be enqueued in the channel feeding the threadpool
/// workers.
const CHANNEL_BOUND: usize = 1000;

fn socket_arg() -> Arg<'static, 'static> {
    Arg::with_name(SOCKET_ARG_NAME)
        .long(SOCKET_ARG_NAME)
        .short(SOCKET_ARG_SHORT)
        .required(true)
        .value_name(SOCKET_ARG_VALUE_NAME)
        .help(SOCKET_ARG_HELP)
}

fn pre_state_hash_arg() -> Arg<'static, 'static> {
    Arg::with_name(PRE_STATE_HASH_ARG_NAME)
        .long(PRE_STATE_HASH_ARG_NAME)
        .short(PRE_STATE_HASH_ARG_SHORT)
        .required(true)
        .value_name(PRE_STATE_HASH_ARG_VALUE_NAME)
        .help(PRE_STATE_HASH_ARG_HELP)
}

fn thread_count_arg() -> Arg<'static, 'static> {
    Arg::with_name(THREAD_COUNT_ARG_NAME)
        .long(THREAD_COUNT_ARG_NAME)
        .short(THREAD_COUNT_ARG_SHORT)
        .default_value(THREAD_COUNT_ARG_DEFAULT)
        .value_name(THREAD_COUNT_ARG_VALUE_NAME)
        .help(THREAD_COUNT_ARG_HELP)
}

fn request_count_arg() -> Arg<'static, 'static> {
    Arg::with_name(REQUEST_COUNT_ARG_NAME)
        .long(REQUEST_COUNT_ARG_NAME)
        .short(REQUEST_COUNT_ARG_SHORT)
        .default_value(REQUEST_COUNT_ARG_DEFAULT)
        .value_name(REQUEST_COUNT_ARG_VALUE_NAME)
        .help(REQUEST_COUNT_ARG_HELP)
}

fn parse_hash(encoded_hash: &str) -> Vec<u8> {
    base16::decode_lower(encoded_hash).expect("Expected a valid, hex-encoded hash")
}

fn parse_count(encoded_thread_count: &str) -> usize {
    let count: usize = encoded_thread_count
        .parse()
        .expect("Expected an integral count");
    if count == 0 {
        panic!("Expected count > 0");
    }
    count
}

struct Args {
    socket: String,
    pre_state_hash: Vec<u8>,
    thread_count: usize,
    request_count: usize,
}

impl Args {
    fn new() -> Self {
        let arg_matches = App::new(APP_NAME)
            .version(crate_version!())
            .about(ABOUT)
            .arg(socket_arg())
            .arg(pre_state_hash_arg())
            .arg(thread_count_arg())
            .arg(request_count_arg())
            .get_matches();

        let socket = arg_matches
            .value_of(SOCKET_ARG_NAME)
            .expect("Expected path to socket file")
            .to_string();
        let pre_state_hash = arg_matches
            .value_of(PRE_STATE_HASH_ARG_NAME)
            .map(parse_hash)
            .expect("Expected a pre-state hash");
        let thread_count = arg_matches
            .value_of(THREAD_COUNT_ARG_NAME)
            .map(parse_count)
            .expect("Expected thread count");
        let request_count = arg_matches
            .value_of(REQUEST_COUNT_ARG_NAME)
            .map(parse_count)
            .expect("Expected request count");

        Args {
            socket,
            pre_state_hash,
            thread_count,
            request_count,
        }
    }
}

/// A record of the durations it took to receive responses to all requests.
#[derive(Clone)]
struct Results {
    durations: Arc<Mutex<Vec<Vec<Duration>>>>,
}

impl Results {
    fn new(args: &Args) -> Self {
        Self {
            durations: Arc::new(Mutex::new(vec![Vec::with_capacity(args.request_count)])),
        }
    }

    /// Records a new duration for the results at `index`.
    fn record(&self, index: usize, duration: Duration) {
        let mut durations = self.durations.lock().expect("Expected to lock mutex");
        durations[index].push(duration);
    }

    /// Returns the number of recorded durations and their mean for the results at `index`.
    fn count_and_mean(&self, index: usize) -> Option<(usize, Duration)> {
        let all_durations = self.durations.lock().expect("Expected to lock mutex");
        let durations = all_durations.get(index)?;
        if durations.is_empty() {
            return None;
        }
        let mean = Duration::sum(durations.iter()) / durations.len() as u32;
        Some((durations.len(), mean))
    }
}

/// A stateful container for the data needed by the client to execute a request and record the
/// results.
///
/// In its initial state, it is passed via the channel to the client to be executed on the
/// threadpool.  Its state is then changed by the client to hold info while the request is being
/// processed; i.e. it's passed from the request future to response handler.  Finally, in the
/// response handler, it's consumed and records the result of the work.
enum Work {
    Initial {
        /// A specific `execute` request to be sent to the server.
        request: ExecuteRequest,
        /// The index of the request in the `WorkProducer::requests` collection.
        index: usize,
        /// The number of request (zero-indexed).
        request_num: usize,
    },
    AfterSendingRequest {
        /// The time at which the request was sent.
        sent: Instant,
        /// The index of the request in the `WorkProducer::requests` collection.
        index: usize,
        /// The number of request (zero-indexed).
        request_num: usize,
        /// The results to be updated in the response handler.
        results: Results,
    },
}

impl Work {
    fn new(request: ExecuteRequest, index: usize, request_num: usize) -> Self {
        Work::Initial {
            request,
            index,
            request_num,
        }
    }

    /// Transition from initial state, returning the contained `request` for sending by the client,
    /// and the updated `Work` object.
    fn transition(self, results: Results) -> (Self, ExecuteRequest) {
        match self {
            Work::Initial {
                request,
                index,
                request_num,
            } => {
                let work = Work::AfterSendingRequest {
                    sent: Instant::now(),
                    index,
                    request_num,
                    results,
                };
                (work, request)
            }
            _ => panic!("Can't transition"),
        }
    }

    /// Finish the work, recording the elapsed duration in the results.
    fn complete(self) {
        match self {
            Work::AfterSendingRequest {
                sent,
                index,
                request_num,
                results,
            } => {
                let duration = Instant::now() - sent;
                results.record(index, duration);
                info!(
                    "Client received successful response {} on {} in {:?}",
                    request_num,
                    thread::current()
                        .name()
                        .expect("Expected current thread to be named"),
                    duration
                );
            }
            _ => panic!("Can't complete"),
        }
    }

    fn request_num(&self) -> usize {
        match self {
            Work::Initial { request_num, .. } | Work::AfterSendingRequest { request_num, .. } => {
                *request_num
            }
        }
    }
}

/// A pseudo-queue of `count` duplicate `ExecuteRequest`s.
#[derive(Clone)]
struct WorkQueue {
    request: ExecuteRequest,
    count: usize,
}

impl WorkQueue {
    fn new(request: ExecuteRequest, count: usize) -> Self {
        Self { request, count }
    }
}

impl Iterator for WorkQueue {
    type Item = ExecuteRequest;

    fn next(&mut self) -> Option<Self::Item> {
        if self.count > 0 {
            self.count -= 1;
            Some(self.request.clone())
        } else {
            None
        }
    }
}

/// A struct which holds queues of `ExecuteRequest`s and which sends these to the client for
/// processing.
struct WorkProducer {
    queues: Vec<WorkQueue>,
    // This is optional so it can be dropped in order to terminate the flow of work being received.
    sender: Option<Sender<Work>>,
}

impl WorkProducer {
    fn new(args: &Args) -> (Self, Receiver<Work>) {
        let amount = U512::one();
        let account_1_public_key = profiling_common::account_1_public_key();
        let account_2_public_key = profiling_common::account_2_public_key();
        let request = ExecuteRequestBuilder::standard(
            account_1_public_key.value(),
            CONTRACT_NAME,
            (account_2_public_key, amount),
        )
        .with_pre_state_hash(&args.pre_state_hash)
        .build();

        let queues = vec![WorkQueue::new(request, args.request_count)];
        let (sender, receiver) = crossbeam_channel::bounded(CHANNEL_BOUND);
        let work_producer = Self {
            queues,
            sender: Some(sender),
        };
        (work_producer, receiver)
    }

    /// Pops `Work` instances from the `WorkQueues` and sends them on the channel.  The returned
    /// thread is blocked until all `WorkQueues` are emptied.
    ///
    /// Once all queues are empty, the channel sender is dropped, allowing the receivers to
    /// eventually break out of their receiving loops.
    fn produce(mut self) -> JoinHandle<()> {
        thread::spawn(move || {
            let sender = self.sender.take().expect("Expected self.sender to be Some");
            let mut request_num = 0;
            for (index, queue) in self.queues.drain(..).enumerate() {
                for request in queue {
                    info!("Producer sending work item {} to Client", request_num);
                    let work = Work::new(request, index, request_num);
                    request_num += 1;
                    sender.send(work).expect("Expected to send work");
                }
            }
        })
    }
}

#[derive(Clone)]
struct Client {
    ee_client: Arc<ExecutionEngineServiceClient>,
    work_receiver: Receiver<Work>,
    threadpool: CpuPool,
    results: Results,
}

impl Client {
    fn new(args: &Args, work_receiver: Receiver<Work>, results: Results) -> Self {
        let client_config = Default::default();
        let ee_client = Arc::new(
            ExecutionEngineServiceClient::new_plain_unix(args.socket.as_str(), client_config)
                .expect("Expected to create Test Client"),
        );
        info!("Client connected on Socket({})", args.socket.as_str());

        let threadpool = futures_cpupool::Builder::new()
            .pool_size(args.thread_count)
            .name_prefix(THREAD_PREFIX)
            .create();

        Self {
            ee_client,
            work_receiver,
            threadpool,
            results,
        }
    }

    /// Using the threadpool to execute tasks, this pulls work off the channel, sends the contained
    /// request to the server and handles the response.
    fn run(&self) -> CpuFuture<(), ()> {
        let client = self.clone();
        let stream = stream::iter_ok::<_, ()>(self.work_receiver.clone());
        self.threadpool.spawn_fn(|| {
            stream.for_each(move |work| {
                client
                    .send_request(work)
                    .and_then(move |(work, response)| Self::handle_response(work, response))
            })
        })
    }

    fn send_request(&self, work: Work) -> impl Future<Item = (Work, ExecuteResponse), Error = ()> {
        info!(
            "Client sending 'execute' request {} on {}",
            work.request_num(),
            thread::current()
                .name()
                .expect("Expected current thread to be named")
        );

        let (work, request) = work.transition(self.results.clone());
        self.ee_client
            .execute(RequestOptions::new(), request)
            .drop_metadata()
            .map(move |response| (work, response))
            .map_err(|error| {
                panic!("Received {:?}", error);
            })
    }

    fn handle_response(work: Work, response: ExecuteResponse) -> Result<(), ()> {
        let deploy_result = response
            .get_success()
            .get_deploy_results()
            .get(0)
            .expect("Expected single deploy result");
        if !deploy_result.has_execution_result() {
            panic!("Expected ExecutionResult, got {:?} instead", deploy_result);
        }
        if deploy_result.get_execution_result().has_error() {
            panic!(
                "Expected successful execution result, but instead got: {:?}",
                deploy_result.get_execution_result().get_error(),
            );
        }
        work.complete();
        Ok(())
    }
}

fn main() {
    env_logger::init();

    let args = Args::new();
    let (work_producer, receiver) = WorkProducer::new(&args);
    let results = Results::new(&args);
    let client = Client::new(&args, receiver, results.clone());

    let start = Instant::now();

    let client_future = client.run();
    let producer_thread = work_producer.produce();
    client_future
        .wait()
        .expect("Expected to join client threadpool");
    producer_thread
        .join()
        .expect("Expected to join the producer thread");

    let overall_duration = Instant::now() - start;

    if let Some((response_count, mean_duration)) = results.count_and_mean(0) {
        println!(
            "Server handled {} requests in {:?} with an average response time of {:?}",
            response_count, overall_duration, mean_duration
        );
    }
}
