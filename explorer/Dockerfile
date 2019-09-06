FROM node:12.5.0-stretch-slim

COPY server/node_modules /app/node_modules
COPY contracts/client/transfer_to_account.wasm /app/contracts/transfer.wasm
COPY contracts/client/standard_payment.wasm /app/contracts/payment.wasm
COPY contracts/explorer/faucet.wasm /app/contracts/faucet.wasm
COPY server/dist /app/server
COPY ui/build /app/ui

WORKDIR /app
ENV STATIC_ROOT=/app/ui
ENV PAYMENT_AMOUNT=1000000
ENV PAYMENT_CONTRACT_PATH=/app/contracts/payment.wasm
ENV FAUCET_CONTRACT_PATH=/app/contracts/faucet.wasm
ENTRYPOINT node server/server.js
