extern crate grpc;
extern crate protobuf;
extern crate execution_engine;

pub mod engine_server;

use engine_server::*;
use execution_engine::engine::Engine;

fn main() {
    let server_builder = engine_server::new("/tmp/socket.sock", Engine::new());
    let _server =  server_builder.build().expect("Start server");

    // loop idefinitely
    loop {
        std::thread::park();
    }

}
