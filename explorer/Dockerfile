FROM node:12.5.0-stretch-slim

COPY server/node_modules /app/node_modules
COPY contracts/target/wasm32-unknown-unknown/release/transfer.wasm /app/contracts/transfer.wasm
COPY contracts/target/wasm32-unknown-unknown/release/faucet.wasm /app/contracts/faucet.wasm
COPY server/dist /app/server
COPY ui/build /app/ui

WORKDIR /app
ENV STATIC_ROOT=/app/ui
ENV FAUCET_CONTRACT_PATH=/app/contracts/faucet.wasm
ENV FAUCET_NONCE_PATH=/app/nonce.txt
ENTRYPOINT node server/server.js
