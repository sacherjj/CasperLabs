build-smart-contract:
	cargo build --release --target wasm32-unknown-unknown -p erc20-smart-contract

test-smart-contract: build-smart-contract
	cargo test --lib -p erc20-smart-contract-test

test-logic:
	cargo test --lib -p erc20-logic