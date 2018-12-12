extern crate protoc_rust_grpc;

use protoc_rust_grpc::{run, Args};

fn main() {
    run(Args {
        out_dir: "src/",
        input: &["../../models/src/main/protobuf/ipc.proto"],
        includes: &["../../models/src/main/protobuf"],
        rust_protobuf: true, // also generate protobuf messages, not just services,
        ..Default::default()
    }).expect("protoc");
}
