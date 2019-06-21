extern crate clap;
extern crate ctrlc;
extern crate dirs;
extern crate grpc;
#[macro_use]
extern crate lazy_static;
extern crate lmdb;

extern crate casperlabs_engine_grpc_server;
extern crate execution_engine;
extern crate shared;
extern crate storage;

use std::collections::btree_map::BTreeMap;
use std::fs;
use std::path::PathBuf;
use std::str::FromStr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use clap::{App, Arg, ArgMatches};
use dirs::home_dir;
use execution_engine::engine_state::EngineState;
use lmdb::DatabaseFlags;

use shared::logging::log_settings::{LogLevelFilter, LogSettings};
use shared::logging::{log_level, log_settings};
use shared::newtypes::CorrelationId;
use shared::os::get_page_size;
use shared::{init, logging, socket};
use storage::global_state::lmdb::LmdbGlobalState;
use storage::trie_store::lmdb::{LmdbEnvironment, LmdbTrieStore};

use casperlabs_engine_grpc_server::engine_server;

// exe / proc
const PROC_NAME: &str = "casperlabs-engine-grpc-server";
const APP_NAME: &str = "CasperLabs Execution Engine Server";
const SERVER_START_MESSAGE: &str = "starting Execution Engine Server";
const SERVER_LISTENING_TEMPLATE: &str = "{listener} is listening on socket: {socket}";
const SERVER_START_EXPECT: &str = "failed to start Execution Engine Server";
const SERVER_STOP_MESSAGE: &str = "stopping Execution Engine Server";

// data-dir / lmdb
const ARG_DATA_DIR: &str = "data-dir";
const ARG_DATA_DIR_SHORT: &str = "d";
const ARG_DATA_DIR_VALUE: &str = "DIR";
const ARG_DATA_DIR_HELP: &str = "Sets the data directory";
const DEFAULT_DATA_DIR_RELATIVE: &str = ".casperlabs";
const GLOBAL_STATE_DIR: &str = "global_state";
const GET_HOME_DIR_EXPECT: &str = "Could not get home directory";
const CREATE_DATA_DIR_EXPECT: &str = "Could not create directory";
const LMDB_ENVIRONMENT_EXPECT: &str = "Could not create LmdbEnvironment";
const LMDB_TRIE_STORE_EXPECT: &str = "Could not create LmdbTrieStore";
const LMDB_GLOBAL_STATE_EXPECT: &str = "Could not create LmdbGlobalState";

// pages / lmdb
const ARG_PAGES: &str = "pages";
const ARG_PAGES_SHORT: &str = "p";
const ARG_PAGES_VALUE: &str = "NUM";
const ARG_PAGES_HELP: &str = "Sets the max number of pages to use for lmdb's mmap";
const GET_PAGES_EXPECT: &str = "Could not parse pages argument";
// 1 GiB = 1073741824 bytes
// page size on x86_64 linux = 4096 bytes
// 1073741824 / 4096 = 262144
const DEFAULT_PAGES: usize = 262_144;

// socket
const ARG_SOCKET: &str = "socket";
const ARG_SOCKET_HELP: &str = "socket file";
const ARG_SOCKET_EXPECT: &str = "socket required";
const REMOVING_SOCKET_FILE_MESSAGE: &str = "removing old socket file";
const REMOVING_SOCKET_FILE_EXPECT: &str = "failed to remove old socket file";

const VALIDATE_NONCE: &str = "validate-nonce";

// loglevel
const ARG_LOG_LEVEL: &str = "loglevel";
const ARG_LOG_LEVEL_VALUE: &str = "LOGLEVEL";
const ARG_LOG_LEVEL_HELP: &str = "[ fatal | error | warning | info | debug ]";

// runnable
const SIGINT_HANDLE_EXPECT: &str = "Error setting Ctrl-C handler";
const RUNNABLE_CHECK_INTERVAL_SECONDS: u64 = 3;

// Command line arguments instance
lazy_static! {
    static ref ARG_MATCHES: clap::ArgMatches<'static> = get_args();
}

// LogSettings instance to be used within this application
lazy_static! {
    static ref LOG_SETTINGS: log_settings::LogSettings = get_log_settings();
}

fn main() {
    set_panic_hook();

    log_settings::set_log_settings_provider(&*LOG_SETTINGS);

    logging::log_info(SERVER_START_MESSAGE);

    let matches: &clap::ArgMatches = &*ARG_MATCHES;

    let socket = get_socket(matches);

    match socket.remove_file() {
        Err(e) => panic!("{}: {:?}", REMOVING_SOCKET_FILE_EXPECT, e),
        Ok(_) => logging::log_info(REMOVING_SOCKET_FILE_MESSAGE),
    };

    let data_dir = get_data_dir(matches);

    let map_size = get_map_size(matches);

    let nonce_check = get_nonce_check(matches);

    let _server = get_grpc_server(&socket, data_dir, map_size, nonce_check);

    log_listening_message(&socket);

    let interval = Duration::from_secs(RUNNABLE_CHECK_INTERVAL_SECONDS);

    let runnable = get_sigint_handle();

    while runnable.load(Ordering::SeqCst) {
        std::thread::park_timeout(interval);
    }

    logging::log_info(SERVER_STOP_MESSAGE);
}

/// Sets panic hook for logging panic info
fn set_panic_hook() {
    let hook: Box<dyn Fn(&std::panic::PanicInfo) + 'static + Sync + Send> =
        Box::new(move |panic_info| {
            match panic_info.payload().downcast_ref::<&str>() {
                Some(s) => {
                    let panic_message = format!("{:?}", s);
                    logging::log_fatal(&panic_message);
                }
                None => {
                    let panic_message = format!("{:?}", panic_info);
                    logging::log_fatal(&panic_message);
                }
            }

            logging::log_info(SERVER_STOP_MESSAGE);
        });
    std::panic::set_hook(hook);
}

/// Gets command line arguments
fn get_args() -> ArgMatches<'static> {
    App::new(APP_NAME)
        .arg(
            Arg::with_name(ARG_LOG_LEVEL)
                .required(false)
                .long(ARG_LOG_LEVEL)
                .takes_value(true)
                .value_name(ARG_LOG_LEVEL_VALUE)
                .help(ARG_LOG_LEVEL_HELP),
        )
        .arg(
            Arg::with_name(ARG_DATA_DIR)
                .short(ARG_DATA_DIR_SHORT)
                .long(ARG_DATA_DIR)
                .value_name(ARG_DATA_DIR_VALUE)
                .help(ARG_DATA_DIR_HELP)
                .takes_value(true),
        )
        .arg(
            Arg::with_name(ARG_PAGES)
                .short(ARG_PAGES_SHORT)
                .long(ARG_PAGES)
                .value_name(ARG_PAGES_VALUE)
                .help(ARG_PAGES_HELP)
                .takes_value(true),
        )
        .arg(
            Arg::with_name(ARG_SOCKET)
                .required(true)
                .help(ARG_SOCKET_HELP)
                .index(1),
        )
        .arg(Arg::with_name(VALIDATE_NONCE).required(false))
        .get_matches()
}

/// Gets SIGINT handle to allow clean exit
fn get_sigint_handle() -> Arc<AtomicBool> {
    let handle = Arc::new(AtomicBool::new(true));
    let h = handle.clone();
    ctrlc::set_handler(move || {
        h.store(false, Ordering::SeqCst);
    })
    .expect(SIGINT_HANDLE_EXPECT);
    handle
}

/// Gets value of socket argument
fn get_socket(matches: &ArgMatches) -> socket::Socket {
    let socket = matches.value_of(ARG_SOCKET).expect(ARG_SOCKET_EXPECT);

    socket::Socket::new(socket.to_owned())
}

/// Gets value of data-dir argument
fn get_data_dir(matches: &ArgMatches) -> PathBuf {
    let mut buf = matches.value_of(ARG_DATA_DIR).map_or(
        {
            let mut dir = home_dir().expect(GET_HOME_DIR_EXPECT);
            dir.push(DEFAULT_DATA_DIR_RELATIVE);
            dir
        },
        PathBuf::from,
    );
    buf.push(GLOBAL_STATE_DIR);
    fs::create_dir_all(&buf).unwrap_or_else(|_| panic!("{}: {:?}", CREATE_DATA_DIR_EXPECT, buf));
    buf
}

///  Parses pages argument and returns map size
fn get_map_size(matches: &ArgMatches) -> usize {
    let page_size = get_page_size().unwrap();
    let pages = matches
        .value_of(ARG_PAGES)
        .map_or(Ok(DEFAULT_PAGES), usize::from_str)
        .expect(GET_PAGES_EXPECT);
    page_size * pages
}

fn get_nonce_check(matches: &ArgMatches) -> bool {
    matches.is_present(VALIDATE_NONCE)
}

/// Builds and returns a gRPC server.
fn get_grpc_server(
    socket: &socket::Socket,
    data_dir: PathBuf,
    map_size: usize,
    nonce_check: bool,
) -> grpc::Server {
    let engine_state = get_engine_state(data_dir, map_size, nonce_check);

    engine_server::new(socket.as_str(), engine_state)
        .build()
        .expect(SERVER_START_EXPECT)
}

/// Builds and returns engine global state
fn get_engine_state(
    data_dir: PathBuf,
    map_size: usize,
    nonce_check: bool,
) -> EngineState<LmdbGlobalState> {
    let environment = {
        let ret = LmdbEnvironment::new(&data_dir, map_size).expect(LMDB_ENVIRONMENT_EXPECT);
        Arc::new(ret)
    };

    let trie_store = {
        let ret = LmdbTrieStore::new(&environment, None, DatabaseFlags::empty())
            .expect(LMDB_TRIE_STORE_EXPECT);
        Arc::new(ret)
    };

    let global_state = {
        let init_state = init::mocked_account([48u8; 32]);
        LmdbGlobalState::from_pairs(
            CorrelationId::new(),
            Arc::clone(&environment),
            Arc::clone(&trie_store),
            &init_state,
        )
        .expect(LMDB_GLOBAL_STATE_EXPECT)
    };

    EngineState::new(global_state, nonce_check)
}

/// Builds and returns log_settings
fn get_log_settings() -> log_settings::LogSettings {
    let matches: &clap::ArgMatches = &*ARG_MATCHES;

    let log_level_filter = LogLevelFilter::from_input(matches.value_of(ARG_LOG_LEVEL));

    LogSettings::new(PROC_NAME, log_level_filter)
}

/// Logs listening on socket message
fn log_listening_message(socket: &socket::Socket) {
    let mut properties: BTreeMap<String, String> = BTreeMap::new();

    properties.insert("listener".to_string(), PROC_NAME.to_owned());
    properties.insert("socket".to_string(), socket.value());

    logging::log_details(
        log_level::LogLevel::Info,
        (&*SERVER_LISTENING_TEMPLATE).to_string(),
        properties,
    );
}
