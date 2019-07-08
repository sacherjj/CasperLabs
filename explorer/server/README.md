# CasperLabs Explorer Server

This is the server side component for the CasperLabs Explorer:
* serves the static files for the UI
* provide an API for the faucet

## Available scripts

### `npm run dev`

Rebuilds and restarts the server whenever there's a source code change.

### `npm start:both`

Runs the server and the UI in development mode. The UI will proxy to the server on its own port, so you can reach API without CORS issues on port 8000, or directly at port 8001. The client can be started in a separate terminal as well.

### `npm run build`

Build artifacts into the `dist` directory.

The `grpc` directory contains auto-generated files, they were addes as follows:

```console
cd src
ln -s ../../grpc/generated grpc
```

## Useful links:
* https://facebook.github.io/create-react-app/docs/deployment
* https://www.fullstackreact.com/articles/using-create-react-app-with-a-server/
* https://auth0.com/docs/quickstart/spa/vanillajs/02-calling-an-api
* https://developer.okta.com/blog/2018/11/15/node-express-typescript
* https://hackernoon.com/interface-grpc-with-web-using-grpc-web-and-envoy-possibly-the-best-way-forward-3ae9671af67
* https://blog.envoyproxy.io/envoy-and-grpc-web-a-fresh-new-alternative-to-rest-6504ce7eb880
