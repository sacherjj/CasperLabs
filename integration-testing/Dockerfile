FROM rust
WORKDIR /tmp
RUN apt-get update && apt-get -yq install unzip
RUN curl -OL https://github.com/protocolbuffers/protobuf/releases/download/v3.6.1/protoc-3.6.1-linux-x86_64.zip
RUN unzip -o protoc-3.6.1-linux-x86_64.zip -d /usr/local bin/protoc
WORKDIR /usr/src
COPY . .
RUN rustup default $(tail -n 1 /usr/src/execution-engine/rust-toolchain)
RUN rustup update
WORKDIR /usr/src/execution-engine/comm
RUN cargo update
RUN cargo run --bin grpc-protoc
RUN cargo build --release

FROM io.casperlabs/node:latest
RUN apt-get update && apt-get -yq install curl sed nmap
WORKDIR /opt/docker/bin
COPY --from=0 /usr/src/execution-engine/comm/target/release/engine-grpc-server .
COPY integration-testing/bootstrap .
ENTRYPOINT ["/opt/docker/bin/bootstrap"]