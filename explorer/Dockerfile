FROM node:12.5.0-stretch-slim

COPY server/node_modules /app/node_modules
COPY faucet-contract/target/wasm32-unknown-unknown/release/faucet_contract.wasm /app/contracts/faucet_contract.wasm
COPY server/dist /app
COPY ui/build /app/static

WORKDIR /app
ENV SERVER_PORT=8080
ENV FAUCET_CONTRACT_PATH=/app/contracts/faucet_contract.wasm
ENV FAUCET_NONCE_PATH=/app/nonce.txt
ENTRYPOINT node server.js
