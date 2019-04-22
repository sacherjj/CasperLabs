extern crate clap;
extern crate common;
extern crate execution_engine;
extern crate grpc;
extern crate protobuf;
extern crate shared;
extern crate storage;
extern crate wabt;
extern crate wasm_prep;

pub mod engine_server;

use clap::{App, Arg};
use engine_server::*;
use execution_engine::engine::EngineState;
use storage::global_state::inmem::InMemHist;

fn main() {
    let matches = App::new("Execution engine server")
        .arg(Arg::with_name("socket").required(true).help("Socket file"))
        .get_matches();
    let socket = matches
        .value_of("socket")
        .expect("missing required argument");
    let socket_path = std::path::Path::new(socket);
    if socket_path.exists() {
        std::fs::remove_file(socket_path).expect("Remove old socket file.");
    }

    let init_state = storage::global_state::mocked_account([48u8; 20]);
    let engine_state =
        EngineState::new(InMemHist::new_initialized(&([0u8; 32].into()), init_state));
    let server_builder = engine_server::new(socket, engine_state);
    let _server = server_builder.build().expect("Start server");

    println!("Server is listening on socket: {}", socket);

    // loop indefinitely
    loop {
        std::thread::park();
    }
}
