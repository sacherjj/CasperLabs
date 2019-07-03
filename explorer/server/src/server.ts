import dotenv from "dotenv";
import express from "express";
import jwt from "express-jwt";
import jwksRsa from "jwks-rsa";
import path from "path";
import { Deploy } from "../../grpc/src/io/casperlabs/casper/consensus/consensus_pb";
import config from "./config.json";

// https://auth0.com/docs/quickstart/spa/vanillajs/02-calling-an-api
// https://github.com/auth0/express-jwt

// initialize configuration
dotenv.config();

// port is now available to the Node.js runtime
// as if it were an environment variable
const port = process.env.SERVER_PORT;

// TODO: Everything in config.json could come from env vars.

const app = express();

// create the JWT middleware
const checkJwt = jwt({
  algorithm: ["RS256"],
  audience: config.auth0.audience,
  issuer: `https://${config.auth0.domain}/`,
  secret: jwksRsa.expressJwtSecret({
    cache: true,
    jwksRequestsPerMinute: 5,
    jwksUri: `https://${config.auth0.domain}/.well-known/jwks.json`,
    rateLimit: true,
  }),
});

// Serve the static files of the UI
app.use(express.static(path.join(__dirname, "static")));

app.get("/", (_, res) => {
  res.sendFile(path.join(__dirname, "static", "index.html"));
});

// TODO: Render the `config.js` file dynamically.

// Faucet endpoint.
app.get("/api/faucet", checkJwt, (_, res) => {
  // express-jwt put the token in res.user
  res.send({
    msg: "Your access token was successfully validated!",
  });
});

// Error report in JSON.
app.use((err: any, req: any, res: any, next: any) => {
  if (err.name === "UnauthorizedError") {
    return res.status(401).send({ msg: "Invalid token" });
  }
  next(err, req, res);
});

// start the express server
app.listen(port, () => {
  // tslint:disable-next-line:no-console
  console.log(`server started at http://localhost:${port}`);
});
