FROM ekidd/rust-musl-builder
WORKDIR /tmp
RUN sudo apt-get update && sudo apt-get -yq install unzip
RUN curl -OL https://github.com/protocolbuffers/protobuf/releases/download/v3.6.1/protoc-3.6.1-linux-x86_64.zip
RUN sudo unzip -o protoc-3.6.1-linux-x86_64.zip -d /usr/local bin/protoc
RUN rustup toolchain install nightly
RUN rustup target add x86_64-unknown-linux-musl --toolchain nightly
WORKDIR /home/rust/src
COPY . .
WORKDIR /home/rust/src/execution-engine/comm
RUN cargo +nightly run --bin grpc-protoc
RUN cargo +nightly build --release

FROM io.casperlabs/node:latest
RUN apt-get update && apt-get -yq install curl nmap
WORKDIR /opt/docker/bin
COPY --from=0 /home/rust/src/execution-engine/comm/target/x86_64-unknown-linux-musl/release/engine-grpc-server .
COPY integration-testing/bootstrap .
ENTRYPOINT ["/opt/docker/bin/bootstrap"]