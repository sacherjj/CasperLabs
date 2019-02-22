extern crate protoc_rust_grpc;

fn main() {
    protoc_rust_grpc::run(protoc_rust_grpc::Args {
        out_dir: "src/engine_server",
        input: &["../../models/src/main/protobuf/ipc.proto"],
        includes: &["../../models/src/main/protobuf"],
        rust_protobuf: true,
        ..Default::default()
    }).expect("protoc-rust-grpc");
}
