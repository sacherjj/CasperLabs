extern crate clap;
extern crate execution_engine;
extern crate grpc;
extern crate protobuf;

pub mod engine_server;

use clap::{App, Arg};
use engine_server::*;
use execution_engine::engine::EngineState;
use std::sync::{Arc, Mutex};

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

    let engine_state: EngineState<Vec<u8>> = EngineState::new(Vec::new());
    let server_builder = engine_server::new(socket, engine_state);
    let _server = server_builder.build().expect("Start server");

    println!("Server is listening on socket: {}", socket);

    // loop idefinitely
    loop {
        std::thread::park();
    }
}
