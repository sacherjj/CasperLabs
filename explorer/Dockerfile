FROM node:12.5.0-stretch-slim

RUN npm install -g serve
COPY ui/build /app/build

# https://facebook.github.io/create-react-app/docs/deployment
# Serves on port 5000 by default.
ENTRYPOINT serve -s /app/build
