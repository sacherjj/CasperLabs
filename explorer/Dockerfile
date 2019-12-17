FROM node:12.5.0-stretch-slim

COPY contracts/transfer_to_account.wasm /app/contracts/transfer.wasm
COPY contracts/standard_payment.wasm /app/contracts/payment.wasm
COPY contracts/faucet.wasm /app/contracts/faucet.wasm

COPY server/node_modules /app/server/node_modules
# Copying the local projects because after a build the server node_modules contains symlinks.
COPY grpc /app/grpc
COPY sdk /app/sdk
COPY server/dist /app/server
COPY ui/build /app/ui

WORKDIR /app/server
ENV STATIC_ROOT=/app/ui
ENV PAYMENT_AMOUNT=10000000
ENV TRANSFER_AMOUNT=1000000000
ENV GAS_PRICE=10
ENV PAYMENT_CONTRACT_PATH=/app/contracts/payment.wasm
ENV FAUCET_CONTRACT_PATH=/app/contracts/faucet.wasm
ENTRYPOINT node server.js
