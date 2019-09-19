# Overview

This directory contains executable targets to allow for profiling code used to execute a simple transfer contract.

First, run `state-initializer` to set up a persistent global state, then the `simple-transfer` executable will make use of that state, and can be profiled.

For more details on each, run the executable with `--help`.

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


# Troubleshooting

Due to kernel hardening, `perf` may need some or all of the following changes to be made in order to run properly:


## Error message about `perf_event_paranoid`:

See [this superuser answer](https://superuser.com/a/980757/463043) for details.  In summary, to temporarily fix the issue:

```bash
sudo sysctl -w kernel.perf_event_paranoid=-1
```

and to permanently fix it:

```bash
sudo sh -c 'echo kernel.perf_event_paranoid=-1 >> /etc/sysctl.d/99-my-settings-local.conf'
sysctl -p /etc/sysctl.conf
```


## Error message about `kptr_restrict`:

See [this S.O. answer](https://stackoverflow.com/a/36263349/2556117) for details.  In summary, to temporarily fix the issue:

```bash
sudo sysctl -w kernel.kptr_restrict=0
```

and to permanently fix it:

```bash
sudo sh -c 'echo kernel.kptr_restrict=0 >> /etc/sysctl.d/99-my-settings-local.conf'
sysctl -p /etc/sysctl.conf
```
