extern crate protoc_rust_grpc;

fn main() {
    println!("cargo:rerun-if-changed=../../protobuf/io/casperlabs/casper/consensus/state.proto");
    println!("cargo:rerun-if-changed=../../protobuf/io/casperlabs/ipc/ipc.proto");
    println!("cargo:rerun-if-changed=../../protobuf/io/casperlabs/ipc/transforms.proto");
    protoc_rust_grpc::run(protoc_rust_grpc::Args {
        out_dir: "src/engine_server",
        input: &[
            "../../protobuf/io/casperlabs/casper/consensus/state.proto",
            "../../protobuf/io/casperlabs/ipc/ipc.proto",
            "../../protobuf/io/casperlabs/ipc/transforms.proto",
        ],
        includes: &[
            "../../protobuf/",
            "../../protobuf/io/casperlabs/casper/consensus",
            "../../protobuf/io/casperlabs/ipc",
        ],
        rust_protobuf: true,
        ..Default::default()
    })
    .expect("protoc-rust-grpc");
}
