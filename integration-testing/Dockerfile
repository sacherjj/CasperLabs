FROM rust
WORKDIR /tmp
RUN apt-get update && apt-get -yq install unzip
RUN curl -OL https://github.com/protocolbuffers/protobuf/releases/download/v3.6.1/protoc-3.6.1-linux-x86_64.zip
RUN unzip -o protoc-3.6.1-linux-x86_64.zip -d /usr/local bin/protoc
RUN rustup -v default nightly-2018-12-04
# $(tail -n 1 /usr/src/execution-engine/rust-toolchain)
RUN rustup -v update
WORKDIR /usr/src
COPY . .
WORKDIR /usr/src/execution-engine/comm
RUN cargo update
RUN cargo build --release

FROM io.casperlabs/node:latest
RUN apt-get update && apt-get -yq install curl sed nmap
WORKDIR /tmp/resources
COPY --from=0 /usr/src/integration-testing/resources  .
WORKDIR /opt/docker/bin
COPY --from=0 /usr/src/execution-engine/target/release/engine-grpc-server .
COPY integration-testing/bootstrap .
ENTRYPOINT ["/opt/docker/bin/bootstrap"]