build-smart-contract:
	cargo build --release --target wasm32-unknown-unknown -p erc20-smart-contract

link-target:
	ln -f -s ../target/ target

test-smart-contract: build-smart-contract link-target
	cargo test --lib -p erc20-smart-contract-test

test-logic:
	cargo test --lib -p erc20-logic

test: test-logic test-smart-contract