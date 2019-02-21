FROM rust
WORKDIR /tmp
RUN apt-get update && apt-get -yq install unzip
RUN curl -OL https://github.com/protocolbuffers/protobuf/releases/download/v3.6.1/protoc-3.6.1-linux-x86_64.zip
RUN unzip -o protoc-3.6.1-linux-x86_64.zip -d /usr/local bin/protoc
RUN rustup toolchain install nightly
RUN rustup target add x86_64-unknown-linux-gnu --toolchain nightly
WORKDIR /usr/src
COPY . .
WORKDIR /usr/src/execution-engine/comm
RUN cargo +nightly run --bin grpc-protoc
RUN cargo +nightly build --release

FROM io.casperlabs/node:latest
#RUN apt-get update && apt-get -yq install curl nmap
WORKDIR /opt/docker/bin
COPY --from=0 /usr/src/execution-engine/comm/target/release/casperlabs-engine-grpc-server .
COPY integration-testing/bootstrap .
ENTRYPOINT ["/opt/docker/bin/bootstrap"]