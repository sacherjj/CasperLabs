extern crate clap;
extern crate common;
extern crate dirs;
extern crate execution_engine;
extern crate grpc;
#[macro_use]
extern crate lazy_static;
extern crate lmdb;
extern crate protobuf;
extern crate shared;
extern crate storage;
extern crate wabt;
extern crate wasm_prep;

pub mod engine_server;

use std::sync::atomic;

use clap::{App, Arg, ArgMatches};
use dirs::home_dir;
use engine_server::*;
use execution_engine::engine::EngineState;
use lmdb::DatabaseFlags;
use shared::logging::log_level::LogLevel;
use shared::logging::log_settings::{LogLevelFilter, LogSettings};
use shared::logging::{log_level, log_settings};
use shared::{logging, socket};
use std::collections::btree_map::BTreeMap;
use std::fs;
use std::path::PathBuf;
use std::sync::Arc;
use storage::global_state::lmdb::LmdbGlobalState;
use storage::history::trie_store::lmdb::{LmdbEnvironment, LmdbTrieStore};

const DEFAULT_DATA_DIR_RELATIVE: &str = ".casperlabs";
const GLOBAL_STATE_DIR: &str = "global_state";

const GET_HOME_DIR_EXPECT: &str = "Could not get home directory";
const CREATE_DATA_DIR_EXPECT: &str = "Could not create directory";
const LMDB_ENVIRONMENT_EXPECT: &str = "Could not create LmdbEnvironment";
const LMDB_TRIE_STORE_EXPECT: &str = "Could not create LmdbTrieStore";
const LMDB_GLOBAL_STATE_EXPECT: &str = "Could not create LmdbGlobalState";

const ARG_SOCKET: &str = "socket";
const ARG_SOCKET_HELP: &str = "socket file";
const ARG_SOCKET_EXPECT: &str = "socket required";
const REMOVING_SOCKET_FILE_MESSAGE: &str = "removing old socket file";
const REMOVING_SOCKET_FILE_EXPECT: &str = "failed to remove old socket file";

const ARG_LOG_LEVEL: &str = "loglevel";
const ARG_LOG_LEVEL_VALUE: &str = "LOGLEVEL";
const ARG_LOG_LEVEL_HELP: &str = "[ fatal | error | warning | info | debug ]";

const ARG_DATA_DIR: &str = "data-dir";
const ARG_DATA_DIR_SHORT: &str = "d";
const ARG_DATA_DIR_VALUE: &str = "DIR";
const ARG_DATA_DIR_HELP: &str = "Sets the data directory";

const PROC_NAME: &str = "Execution Engine Server";
const SERVER_START_MESSAGE: &str = "starting Execution Engine Server";
const SERVER_LISTENING_TEMPLATE: &str = "{listener} is listening on socket: {socket}";
const SERVER_START_EXPECT: &str = "failed to start Execution Engine Server";
#[allow(dead_code)]
const SERVER_STOP_MESSAGE: &str = "stopping Execution Engine Server";

static BREAK: atomic::AtomicBool = atomic::AtomicBool::new(false);

static CHECK_ARGS: atomic::AtomicBool = atomic::AtomicBool::new(false);

lazy_static! {
    static ref ARG_MATCHES: clap::ArgMatches<'static> = get_args();
}
lazy_static! {
    static ref LOG_SETTINGS: log_settings::LogSettings = get_log_settings();
}

fn main() {
    CHECK_ARGS.store(true, atomic::Ordering::SeqCst);

    set_panic_hook();

    log_server_info(SERVER_START_MESSAGE);

    let matches: &clap::ArgMatches = &*ARG_MATCHES;

    let socket = get_socket(matches);

    if socket.file_exists() {
        log_server_info(REMOVING_SOCKET_FILE_MESSAGE);
        socket.remove_file().expect(REMOVING_SOCKET_FILE_EXPECT);
    }

    let server_builder = engine_server::new(socket.as_str(), get_engine_state(matches));

    let _server = server_builder.build().expect(SERVER_START_EXPECT);

    log_listening_message(socket);

    // loop indefinitely
    loop {
        std::thread::park();
        // this is currently unreachable.
        // TODO: recommend we impl signal capture; SIGINT at the very least.
        if BREAK.load(atomic::Ordering::SeqCst) {
            break;
        }
    }

    log_server_info(SERVER_STOP_MESSAGE);
}

fn set_panic_hook() {
    let log_settings_panic = LOG_SETTINGS.clone();
    let hook: Box<dyn Fn(&std::panic::PanicInfo) + 'static + Sync + Send> =
        Box::new(move |panic_info| {
            if let Some(s) = panic_info.payload().downcast_ref::<&str>() {
                let panic_message = format!("{:?}", s);
                logging::log(
                    &log_settings_panic,
                    log_level::LogLevel::Fatal,
                    &panic_message,
                );
            }
        });
    std::panic::set_hook(hook);
}

fn get_socket(matches: &ArgMatches) -> socket::Socket {
    let socket = matches.value_of(ARG_SOCKET).expect(ARG_SOCKET_EXPECT);

    socket::Socket::new(socket.to_owned())
}

fn get_engine_state(matches: &ArgMatches) -> EngineState<LmdbGlobalState> {
    let data_dir: PathBuf = {
        let mut ret = matches.value_of("data-dir").map_or(
            {
                let mut dir = home_dir().expect(GET_HOME_DIR_EXPECT);
                dir.push(DEFAULT_DATA_DIR_RELATIVE);
                dir
            },
            PathBuf::from,
        );
        ret.push(GLOBAL_STATE_DIR);
        fs::create_dir_all(&ret)
            .unwrap_or_else(|_| panic!("{}: {:?}", CREATE_DATA_DIR_EXPECT, ret));
        ret
    };

    let environment = {
        let ret = LmdbEnvironment::new(&data_dir).expect(LMDB_ENVIRONMENT_EXPECT);
        Arc::new(ret)
    };

    let trie_store = {
        let ret = LmdbTrieStore::new(&environment, None, DatabaseFlags::empty())
            .expect(LMDB_TRIE_STORE_EXPECT);
        Arc::new(ret)
    };

    let global_state = {
        let init_state = storage::global_state::mocked_account([48u8; 20]);
        LmdbGlobalState::from_pairs(
            Arc::clone(&environment),
            Arc::clone(&trie_store),
            &init_state,
        )
        .expect(LMDB_GLOBAL_STATE_EXPECT)
    };

    EngineState::new(global_state)
}

fn get_args() -> ArgMatches<'static> {
    App::new(PROC_NAME)
        .arg(
            Arg::with_name(ARG_LOG_LEVEL)
                .required(true)
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
            Arg::with_name(ARG_SOCKET)
                .required(true)
                .help(ARG_SOCKET_HELP)
                .index(1),
        )
        .get_matches()
}

fn get_log_settings() -> log_settings::LogSettings {
    if CHECK_ARGS.load(atomic::Ordering::SeqCst) {
        let matches: &clap::ArgMatches = &*ARG_MATCHES;

        let log_level_filter = get_log_level_filter(matches.value_of(ARG_LOG_LEVEL));

        return LogSettings::new(PROC_NAME, log_level_filter);
    }

    LogSettings::new(
        PROC_NAME,
        log_settings::LogLevelFilter::new(LogLevel::Debug),
    )
}

fn get_log_level_filter(input: Option<&str>) -> LogLevelFilter {
    let log_level = match input {
        Some(input) => match input {
            "fatal" => LogLevel::Fatal,
            "error" => LogLevel::Error,
            "warning" => LogLevel::Warning,
            "debug" => LogLevel::Debug,
            _ => LogLevel::Info,
        },
        None => log_level::LogLevel::Info,
    };

    log_settings::LogLevelFilter::new(log_level)
}

fn log_listening_message(socket: shared::socket::Socket) {
    let mut properties: BTreeMap<String, String> = BTreeMap::new();

    properties.insert("listener".to_string(), PROC_NAME.to_owned());
    properties.insert("socket".to_string(), socket.value());

    logging::log_props(
        &*LOG_SETTINGS,
        log_level::LogLevel::Info,
        (&*SERVER_LISTENING_TEMPLATE).to_string(),
        properties,
    );
}

fn log_server_info(message: &str) {
    logging::log(&*LOG_SETTINGS, log_level::LogLevel::Info, message);
}
