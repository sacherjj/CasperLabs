//! This executable is designed to send a series of contracts in parallel to a running instance of
//! `casperlabs-engine-grpc-server` in order to gauge the server's performance.
//!
//! For details of how to run this executable, see the README in this directory or at
//! https://github.com/CasperLabs/CasperLabs/blob/dev/execution-engine/engine-tests/src/profiling/README.md#concurrent-executor

#[macro_use]
extern crate clap;
extern crate env_logger;
extern crate futures;
extern crate grpc;
#[macro_use]
extern crate log;

extern crate casperlabs_engine_tests as engine_tests;
extern crate contract_ffi;
extern crate engine_core;
extern crate engine_grpc_server;
extern crate engine_shared;

use std::iter::Sum;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use std::{env, thread};

use clap::{App, Arg};
use futures::future::{self, Either, Future, Loop};
use grpc::{ClientStubExt, GrpcFuture, RequestOptions};
use tokio::runtime;

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

#[derive(Debug)]
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

#[derive(Clone)]
struct Work {
    /// A specific `execute` request to be sent to the server.
    request: ExecuteRequest,
    /// The total number of times this request should be sent to the server.
    total_to_send: usize,
    /// The number of times this request has been sent to the server.
    num_sent: Arc<AtomicUsize>,
    /// A record of the durations it took to receive responses to these requests.
    durations: Arc<Mutex<Vec<Duration>>>,
}

impl Work {
    fn new(args: &Args) -> Self {
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

        let num_sent = Arc::new(AtomicUsize::new(0));
        let durations = Arc::new(Mutex::new(Vec::with_capacity(args.request_count)));
        Self {
            request,
            total_to_send: args.request_count,
            num_sent,
            durations,
        }
    }

    /// If we still have some requests to send, returns a clone of the request and the request
    /// number (zero-indexed), while incrementing the sent counter.  Otherwise, if we've sent the
    /// required number of requests, returns `None`.
    fn next_request(&self) -> Option<(ExecuteRequest, usize)> {
        let this_index = self.num_sent.fetch_add(1, Ordering::SeqCst);
        if this_index < self.total_to_send {
            Some((self.request.clone(), this_index))
        } else {
            None
        }
    }

    fn record_duration(&self, duration: Duration) {
        let mut durations = self.durations.lock().expect("Expected to lock mutex");
        durations.push(duration)
    }

    fn average_duration(&self) -> Duration {
        let durations = self.durations.lock().expect("Expected to lock mutex");
        if durations.is_empty() {
            Duration::from_secs(0)
        } else {
            Duration::sum(durations.iter()) / durations.len() as u32
        }
    }
}

#[derive(Clone)]
struct Client {
    ee_client: Arc<ExecutionEngineServiceClient>,
    work: Work,
}

impl Client {
    fn new(args: &Args) -> Self {
        let client_config = Default::default();
        let ee_client = Arc::new(
            ExecutionEngineServiceClient::new_plain_unix(args.socket.as_str(), client_config)
                .expect("Expected to create Test Client"),
        );
        info!("Connected on Socket({})", args.socket.as_str());
        Self {
            ee_client,
            work: Work::new(args),
        }
    }

    fn send_request(&self) -> Option<GrpcFuture<ExecuteResponse>> {
        let (request, request_num) = self.work.next_request()?;
        info!(
            "Sending 'execute' request {} on {}",
            request_num,
            thread::current()
                .name()
                .expect("Expected current thread to be named")
        );
        let response_future = self
            .ee_client
            .execute(RequestOptions::new(), request)
            .drop_metadata();
        Some(response_future)
    }

    fn handle_response(&self, sent_instant: Instant, response: ExecuteResponse) {
        let duration = Instant::now() - sent_instant;
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

        info!(
            "Received successful response on {} in {:?}",
            thread::current()
                .name()
                .expect("Expected current thread to be named"),
            duration
        );

        self.work.record_duration(duration);
    }
}

fn run_while_work(client: Client) -> impl Future<Item = (), Error = ()> {
    future::loop_fn(client, move |client| {
        let start = Instant::now();
        match client.send_request() {
            None => Either::A(future::ok(Loop::Break(()))),
            Some(response_future) => Either::B(
                response_future
                    .and_then(move |response| {
                        client.handle_response(start, response);
                        Ok(Loop::Continue(client))
                    })
                    .map_err(|error| {
                        panic!("Received {:?}", error);
                    }),
            ),
        }
    })
}

fn main() {
    env_logger::init();

    let args = Args::new();
    let client = Client::new(&args);

    let runtime = runtime::Builder::new()
        .core_threads(args.thread_count)
        .name_prefix(THREAD_PREFIX)
        .build()
        .expect("Expected to construct tokio runtime");
    let start = Instant::now();

    let client = runtime
        .block_on_all::<_, _, ()>(future::lazy(move || {
            for _ in 0..args.thread_count {
                tokio::spawn(run_while_work(client.clone()));
            }
            Ok(client)
        }))
        .expect("Expected to join all threads");
    let duration = Instant::now() - start;
    println!(
        "Server handled {} requests in {:?} with an average response time of {:?}",
        client.work.total_to_send,
        duration,
        client.work.average_duration()
    );
}
