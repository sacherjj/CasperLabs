# Overview

This directory contains executable targets to allow for profiling code used to execute a transfer contract.

# `state-initializer`

This is used to initialize global state in preparation for running one of the other executables.  It allows them to avoid taking into account the cost of installing the Proof of Stake and Mint contracts.

It takes a single optional command line argument to specify the directory in which to store the persistent data and outputs the post-state hash from the commit response.  This hash will be used as an input to other profiling executables.

---

# `simple-transfer`

This runs a single transfer via the `LmdbWasmTestBuilder` and is designed to be used along with `perf` to analyse the performance data.

First, run `state-initializer` to set up a persistent global state, then the `simple-transfer` executable will make use of that state, and can be profiled.

For more details on each, run the executable with `--help`.

## Example usage

To profile `simple-transfer` using `perf` and open the flamegraph in Firefox, follow these steps:

* Install `perf` (see [this askubuntu answer](https://askubuntu.com/a/578618/75096))
* Clone and add [Flamegraph](https://github.com/brendangregg/FlameGraph) to your path
* Run:
    ```bash
    cd CasperLabs/execution-engine/
    make build-contracts
    cd engine-tests/
    cargo build --release --bin state-initializer
    cargo build --release --bin simple-transfer
    ../target/release/state-initializer --data-dir=../target | perf record -g --call-graph dwarf ../target/release/simple-transfer --data-dir=../target
    perf script | stackcollapse-perf.pl | flamegraph.pl > flame.svg
    firefox flame.svg
    ```


## Troubleshooting

Due to kernel hardening, `perf` may need some or all of the following changes to be made in order to run properly:


### Error message about `perf_event_paranoid`:

See [this superuser answer](https://superuser.com/a/980757/463043) for details.  In summary, to temporarily fix the issue:

```bash
sudo sysctl -w kernel.perf_event_paranoid=-1
```

and to permanently fix it:

```bash
sudo sh -c 'echo kernel.perf_event_paranoid=-1 >> /etc/sysctl.d/99-my-settings-local.conf'
sysctl -p /etc/sysctl.conf
```


### Error message about `kptr_restrict`:

See [this S.O. answer](https://stackoverflow.com/a/36263349/2556117) for details.  In summary, to temporarily fix the issue:

```bash
sudo sysctl -w kernel.kptr_restrict=0
```

and to permanently fix it:

```bash
sudo sh -c 'echo kernel.kptr_restrict=0 >> /etc/sysctl.d/99-my-settings-local.conf'
sysctl -p /etc/sysctl.conf
```

---

# `concurrent-executor`

This is a minimal client which repeatedly sends an `execute` request for a transfer to an instance of the `casperlabs-engine-grpc-server`.  It runs a threadpool to parallelize sending the requests, and it's designed to allow testing the effects of varying the worker thread count in the server.

## Example usage

First build the contracts and run `state-initializer`:

```bash
cd CasperLabs/execution-engine/
make build-contracts
cd engine-tests/
HASH=$(cargo run --release --bin=state-initializer -- --data-dir=/tmp/CasperLabs/DataDir)
```

In a new terminal, run the server, using the same data directory populated by the `state-initializer`:

```bash
cd CasperLabs/execution-engine/
cargo run --release --bin=casperlabs-engine-grpc-server -- \
    /tmp/CasperLabs/Socket --data-dir=/tmp/CasperLabs/DataDir --threads=8
```

Then in the first terminal, run the client:

```bash
RUST_LOG=concurrent_executor=info cargo run --release --bin=concurrent-executor -- \
    --socket=/tmp/CasperLabs/Socket --pre-state-hash=$HASH --threads=8 --requests=200
```

There is a bash script which automates this process, and which allows specifying the number of server threadpool threads, the number of client threadpool threads, and the number of messages the client should send.

```bash
cd CasperLabs/execution-engine/engine-tests/src/profiling/
./concurrent_executor.sh 8 8 200
```

For logging, again set the `RUST_LOG` env var:

```bash
RUST_LOG=concurrent_executor=info ./concurrent_executor.sh 8 8 200
```
