# This supports environments where $HOME/.cargo/env has not been sourced (CI, CLion Makefile runner)
CARGO = $(or $(shell which cargo), $(HOME)/.cargo/bin/cargo)

# Directory names should match crate names
SYSTEM_CONTRACTS = $(shell find ./contracts/system -mindepth 1 -maxdepth 1 -exec basename {} \;)
TEST_CONTRACTS   = $(shell find ./contracts/test   -mindepth 1 -maxdepth 1 -exec basename {} \;)
CLIENT_CONTRACTS = $(shell find ./contracts/client -mindepth 1 -maxdepth 1 -exec basename {} \;)

SYSTEM_CONTRACTS := $(patsubst %, build-contract/%, $(SYSTEM_CONTRACTS))
TEST_CONTRACTS   := $(patsubst %, build-contract/%, $(TEST_CONTRACTS))
CLIENT_CONTRACTS := $(patsubst %, build-contract/%, $(CLIENT_CONTRACTS))

CONTRACT_TARGET_DIR       = target/wasm32-unknown-unknown/release
PACKAGED_SYSTEM_CONTRACTS = mint_token.wasm pos.wasm

.PHONY: all
all: build build-contracts

.PHONY: build
build:
	$(CARGO) build $(CARGO_FLAGS)

build-contract/%:
	$(CARGO) build $(CARGO_FLAGS) -p $* --release --target wasm32-unknown-unknown

.PHONY: build-contracts
build-contracts: \
	$(SYSTEM_CONTRACTS) \
	$(TEST_CONTRACTS) \
	$(CLIENT_CONTRACTS)

.PHONY: test
test:
	$(CARGO) test $(CARGO_FLAGS) -- --nocapture

.PHONY: test-contracts
test-contracts: $(SYSTEM_CONTRACTS) $(TEST_CONTRACTS)
	$(CARGO) test $(CARGO_FLAGS) -p casperlabs-engine-grpc-server -- --ignored --nocapture

.PHONY: check-format
check-format:
	$(CARGO) fmt --all -- --check

.PHONY: lint
lint:
	$(CARGO) clippy --all-targets --all -- -D warnings -A renamed_and_removed_lints

.PHONY: check
check: \
	check-format \
	lint \
	test \
	test-contracts

.PHONY: clean
clean:
	rm -f comm/.rpm
	$(CARGO) clean

.PHONY: deb
deb:
	cd comm && $(CARGO) deb

comm/.rpm:
	cd comm && $(CARGO) rpm init

.PHONY: rpm
rpm: comm/.rpm
	cd comm && $(CARGO) rpm build

target/system-contracts.tar.gz: $(SYSTEM_CONTRACTS)
	tar -czf $@ -C $(CONTRACT_TARGET_DIR) $(PACKAGED_SYSTEM_CONTRACTS)

.PHONY: package-system-contracts
package-system-contracts: target/system-contracts.tar.gz

.PHONY: publish
publish:
	cd common && $(CARGO) publish

.PHONY: check-publish
check-publish:
	cd common && $(CARGO) publish --dry-run

.PHONY: bench
bench:
	$(CARGO) bench
