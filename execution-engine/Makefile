# This supports environments where $HOME/.cargo/env has not been sourced (CI, CLion Makefile runner)
CARGO  = $(or $(shell which cargo),  $(HOME)/.cargo/bin/cargo)
RUSTUP = $(or $(shell which rustup), $(HOME)/.cargo/bin/rustup)

RUST_TOOLCHAIN := $(shell cat rust-toolchain)

# Directory names should match crate names
BENCH_CONTRACTS     = $(shell find ./contracts/bench     -mindepth 1 -maxdepth 1 -exec basename {} \;)
CLIENT_CONTRACTS    = $(shell find ./contracts/client    -mindepth 1 -maxdepth 1 -exec basename {} \;)
EXPLORER_CONTRACTS  = $(shell find ./contracts/explorer  -mindepth 1 -maxdepth 1 -exec basename {} \;)
PROFILING_CONTRACTS = $(shell find ./contracts/profiling -mindepth 1 -maxdepth 1 -exec basename {} \;)
SRE_CONTRACTS       = $(shell find ./contracts/SRE       -mindepth 1 -maxdepth 1 -exec basename {} \;)
SYSTEM_CONTRACTS    = $(shell find ./contracts/system    -mindepth 1 -maxdepth 1 -exec basename {} \;)
TEST_CONTRACTS      = $(shell find ./contracts/test      -mindepth 1 -maxdepth 1 -exec basename {} \;)

BENCH_CONTRACTS     := $(patsubst %, build-contract/%, $(BENCH_CONTRACTS))
CLIENT_CONTRACTS    := $(patsubst %, build-contract/%, $(CLIENT_CONTRACTS))
EXPLORER_CONTRACTS  := $(patsubst %, build-contract/%, $(EXPLORER_CONTRACTS))
PROFILING_CONTRACTS := $(patsubst %, build-contract/%, $(PROFILING_CONTRACTS))
SRE_CONTRACTS       := $(patsubst %, build-contract/%, $(SRE_CONTRACTS))
SYSTEM_CONTRACTS    := $(patsubst %, build-contract/%, $(SYSTEM_CONTRACTS))
TEST_CONTRACTS      := $(patsubst %, build-contract/%, $(TEST_CONTRACTS))

CONTRACT_TARGET_DIR       = target/wasm32-unknown-unknown/release
PACKAGED_SYSTEM_CONTRACTS = mint_token.wasm pos.wasm

.PHONY: all
all: build build-contracts

.PHONY: build
build: $(SYSTEM_CONTRACTS)
	$(CARGO) build $(CARGO_FLAGS)

build-contract/%:
	$(CARGO) build $(filter-out --release, $(CARGO_FLAGS)) -p $* --release --target wasm32-unknown-unknown

.PHONY: build-contracts
build-contracts: \
	$(BENCH_CONTRACTS) \
	$(CLIENT_CONTRACTS) \
	$(EXPLORER_CONTRACTS) \
	$(PROFILING_CONTRACTS) \
	$(SRE_CONTRACTS) \
	$(SYSTEM_CONTRACTS) \
	$(TEST_CONTRACTS)

.PHONY: test
test: $(SYSTEM_CONTRACTS)
	$(CARGO) test $(CARGO_FLAGS) -- --nocapture

.PHONY: test-contracts
test-contracts: build-contracts
	$(CARGO) test $(CARGO_FLAGS) -p casperlabs-engine-tests -- --ignored --nocapture

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
	check-format \
	build \
	lint \
	test \
	test-contracts

.PHONY: clean
clean:
	rm -f comm/.rpm
	$(CARGO) clean

.PHONY: deb
deb: $(SYSTEM_CONTRACTS)
	cd engine-grpc-server && $(CARGO) deb

engine-grpc-server/.rpm:
	cd engine-grpc-server && $(CARGO) rpm init

.PHONY: rpm
rpm: engine-grpc-server/.rpm $(SYSTEM_CONTRACTS)
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
bench: build-contracts
	$(CARGO) bench

.PHONY: setup-cargo-packagers
setup-cargo-packagers:
	$(CARGO) install cargo-rpm || exit 0
	$(CARGO) install cargo-deb || exit 0

.PHONY: setup
setup: rust-toolchain
	$(RUSTUP) update
	$(RUSTUP) toolchain install $(RUST_TOOLCHAIN)
	$(RUSTUP) target add --toolchain $(RUST_TOOLCHAIN) wasm32-unknown-unknown
	$(RUSTUP) component add --toolchain $(RUST_TOOLCHAIN) rustfmt clippy
