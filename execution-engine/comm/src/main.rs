extern crate clap;
extern crate common;
extern crate dirs;
extern crate execution_engine;
extern crate grpc;
extern crate lmdb;
extern crate protobuf;
extern crate shared;
extern crate storage;
extern crate wabt;
extern crate wasm_prep;

pub mod engine_server;

use clap::{App, Arg};
use dirs::home_dir;
use engine_server::*;
use execution_engine::engine::EngineState;
use lmdb::DatabaseFlags;
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

fn main() {
    let matches = App::new("Execution engine server")
        .arg(Arg::with_name("socket").required(true).help("Socket file"))
        .arg(
            Arg::with_name("data-dir")
                .short("d")
                .long("data-dir")
                .value_name("DIR")
                .help("Sets the data directory")
                .takes_value(true),
        )
        .get_matches();
    let socket = matches
        .value_of("socket")
        .expect("missing required argument");
    let socket_path = std::path::Path::new(socket);
    if socket_path.exists() {
        std::fs::remove_file(socket_path).expect("Remove old socket file.");
    }

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

    let engine_state = EngineState::new(global_state);
    let server_builder = engine_server::new(socket, engine_state);
    let _server = server_builder.build().expect("Start server");

    println!("Server is listening on socket: {}", socket);

    // loop indefinitely
    loop {
        std::thread::park();
    }
}
