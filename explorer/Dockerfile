FROM node:12.5.0-stretch-slim

COPY server/node_modules /app/node_modules
COPY server/dist /app
COPY ui/build /app/static

WORKDIR /app
ENV SERVER_PORT=8080
ENTRYPOINT node server.js
