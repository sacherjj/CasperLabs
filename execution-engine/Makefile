# This supports environments where $HOME/.cargo/env has not been sourced (CI, CLion Makefile runner)
CARGO  = $(or $(shell which cargo),  $(HOME)/.cargo/bin/cargo)
RUSTUP = $(or $(shell which rustup), $(HOME)/.cargo/bin/rustup)
NPM    = $(or $(shell which npm),    /usr/bin/npm)

RUST_TOOLCHAIN := $(shell cat rust-toolchain)

EE_DIR     = $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
IT_RES_DIR = $(realpath $(EE_DIR)/../integration-testing/resources)

# Rust Contracts
# Directory names should match crate names
BENCH_CONTRACTS       = $(shell find ./contracts/bench       -mindepth 1 -maxdepth 1 -type d -exec basename {} \;)
CLIENT_CONTRACTS      = $(shell find ./contracts/client      -mindepth 1 -maxdepth 1 -type d -exec basename {} \;)
EXAMPLE_CONTRACTS     = $(shell find ./contracts/examples    -mindepth 1 -maxdepth 1 -type d -exec basename {} \;)
EXPLORER_CONTRACTS    = $(shell find ./contracts/explorer    -mindepth 1 -maxdepth 1 -type d -exec basename {} \;)
INTEGRATION_CONTRACTS = $(shell find ./contracts/integration -mindepth 1 -maxdepth 1 -type d -exec basename {} \;)
PROFILING_CONTRACTS   = $(shell find ./contracts/profiling   -mindepth 1 -maxdepth 1 -type d -exec basename {} \;)
SRE_CONTRACTS         = $(shell find ./contracts/SRE         -mindepth 1 -maxdepth 1 -type d -exec basename {} \;)
SYSTEM_CONTRACTS      = $(shell find ./contracts/system      -mindepth 1 -maxdepth 1 -type d -exec basename {} \;)
TEST_CONTRACTS        = $(shell find ./contracts/test        -mindepth 1 -maxdepth 1 -type d -exec basename {} \;)

BENCH_CONTRACTS     := $(patsubst %, build-contract-rs/%, $(BENCH_CONTRACTS))
CLIENT_CONTRACTS    := $(patsubst %, build-contract-rs/%, $(CLIENT_CONTRACTS))
EXAMPLE_CONTRACTS   := $(patsubst %, build-contract-rs/%, $(EXAMPLE_CONTRACTS))
EXPLORER_CONTRACTS  := $(patsubst %, build-contract-rs/%, $(EXPLORER_CONTRACTS))
PROFILING_CONTRACTS := $(patsubst %, build-contract-rs/%, $(PROFILING_CONTRACTS))
SRE_CONTRACTS       := $(patsubst %, build-contract-rs/%, $(SRE_CONTRACTS))
SYSTEM_CONTRACTS    := $(patsubst %, build-contract-rs/%, $(SYSTEM_CONTRACTS))
TEST_CONTRACTS      := $(patsubst %, build-contract-rs/%, $(TEST_CONTRACTS))

# AssemblyScript Contracts
CLIENT_CONTRACTS_TS = $(shell find ./contracts-ts/client   -mindepth 1 -maxdepth 1 -type d)

CLIENT_CONTRACTS_TS := $(patsubst %, build-contract-ts/%, $(CLIENT_CONTRACTS_TS))

INTEGRATION_CONTRACTS += \
	bonding-call \
	counter-call \
	counter-define \
	endless-loop \
	hello-name-call \
	hello-name-define \
	local-state \
	mailing-list-call \
	mailing-list-define \
	modified-system-upgrader \
	pos-bonding \
	remove-associated-key \
	standard-payment \
	transfer-to-account \
	unbonding-call

INTEGRATION_CONTRACTS := $(patsubst %, build-integration-contract/%, $(INTEGRATION_CONTRACTS))

CONTRACT_TARGET_DIR       = target/wasm32-unknown-unknown/release
CONTRACT_TARGET_DIR_AS    = target-as
PACKAGED_SYSTEM_CONTRACTS = mint_install.wasm pos_install.wasm

.PHONY: all
all: build build-contracts

.PHONY: build
build:
	$(CARGO) build $(CARGO_FLAGS)

build-contract-rs/%:
	$(CARGO) build \
		--release $(filter-out --release, $(CARGO_FLAGS)) \
		--package $* \
		--target wasm32-unknown-unknown

build-contract-ts/%:
	cd $* && $(NPM) run asbuild

build-integration-contract/%:
	$(CARGO) build \
		-Z unstable-options \
		--release $(filter-out --release, $(CARGO_FLAGS)) \
		--package $* \
		--target wasm32-unknown-unknown \
		--out-dir $(IT_RES_DIR)

.PHONY: build-contracts-rs
build-contracts-rs: \
	$(BENCH_CONTRACTS) \
	$(CLIENT_CONTRACTS) \
	$(EXAMPLE_CONTRACTS) \
	$(EXPLORER_CONTRACTS) \
	$(INTEGRATION_CONTRACTS) \
	$(PROFILING_CONTRACTS) \
	$(SRE_CONTRACTS) \
	$(SYSTEM_CONTRACTS) \
	$(TEST_CONTRACTS)

.PHONY: build-contracts-ts
build-contracts-ts: \
	$(CLIENT_CONTRACTS_TS)

.PHONY: build-contracts
build-contracts: build-contracts-rs build-contracts-ts

.PHONY: build-integration-contracts
build-integration-contracts: $(INTEGRATION_CONTRACTS)

.PHONY: build-example-contracts
build-example-contracts: $(EXAMPLE_CONTRACTS)

.PHONY: test-rs
test-rs:
	$(CARGO) test $(CARGO_FLAGS) --all -- --nocapture

.PHONY: test-ts
test-ts:
	cd contract-ffi-ts && npm run asbuild && npm run test

.PHONY: test
test: test-rs test-ts

.PHONY: test-contracts-rs
test-contracts-rs: build-contracts-rs
	$(CARGO) test $(CARGO_FLAGS) -p casperlabs-engine-tests -- --ignored --nocapture

.PHONY: test-contracts-ts
test-contracts-ts: build-contracts-ts
	$(CARGO) test $(CARGO_FLAGS) -p casperlabs-engine-tests -- --ignored --nocapture

.PHONY: test-contracts
test-contracts: tests-contracts-rs test-contracts-ts

.PHONY: check-format
check-format:
	$(CARGO) fmt --all -- --check

.PHONY: format
format:
	$(CARGO) fmt --all

.PHONY: lint
lint:
	$(CARGO) clippy --all-targets --all -- -D warnings -A renamed_and_removed_lints

.PHONY: check
check: \
	build \
	check-format \
	lint \
	test \
	test-contracts

.PHONY: clean
clean:
	rm -f comm/.rpm
	rm -rf $(CONTRACT_TARGET_DIR_AS)
	$(CARGO) clean

.PHONY: deb
deb:
	cd engine-grpc-server && $(CARGO) deb

engine-grpc-server/.rpm:
	cd engine-grpc-server && $(CARGO) rpm init

.PHONY: rpm
rpm: engine-grpc-server/.rpm
	cd engine-grpc-server && $(CARGO) rpm build

target/system-contracts.tar.gz: $(SYSTEM_CONTRACTS)
	tar -czf $@ -C $(CONTRACT_TARGET_DIR) $(PACKAGED_SYSTEM_CONTRACTS)

.PHONY: package-system-contracts
package-system-contracts: target/system-contracts.tar.gz

.PHONY: package
package:
	cd contract-ffi && $(CARGO) package

.PHONY: publish
publish:
	cd contract-ffi && $(CARGO) publish

.PHONY: check-publish
check-publish:
	cd contract-ffi && $(CARGO) publish --dry-run

.PHONY: bench
bench: build-contracts-rs
	$(CARGO) bench

.PHONY: setup-cargo-packagers
setup-cargo-packagers:
	$(CARGO) install cargo-rpm || exit 0
	$(CARGO) install cargo-deb || exit 0

.PHONY: setup-rs
setup-rs: rust-toolchain
	$(RUSTUP) update
	$(RUSTUP) toolchain install $(RUST_TOOLCHAIN)
	$(RUSTUP) target add --toolchain $(RUST_TOOLCHAIN) wasm32-unknown-unknown
	$(RUSTUP) component add --toolchain $(RUST_TOOLCHAIN) rustfmt clippy

.PHONY: setup-ts
setup-ts: contract-ffi-ts/package.json
	cd contract-ffi-ts && $(NPM) install

.PHONY: setup
setup: setup-rs setup-ts
