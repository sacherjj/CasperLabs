import dotenv from "dotenv";
import express from "express";
import jwt from "express-jwt";
import fs from "fs";
import https from "https";
import jwksRsa from "jwks-rsa";
import path from "path";
import { decodeBase64 } from "tweetnacl-util";
// TODO: Everything in config.json could come from env vars.
import config from "./config.json";
import { BoundContract, Contract, Faucet } from "./lib/Contracts";
import { Ed25519 } from "./lib/Keys";
import DeployService from "./services/DeployService";

// https://auth0.com/docs/quickstart/spa/vanillajs/02-calling-an-api
// https://github.com/auth0/express-jwt

// initialize configuration
dotenv.config();

// port is now available to the Node.js runtime
// as if it were an environment variable
const port = process.env.SERVER_PORT!;

const contractKeys =
  Ed25519.parseKeyFiles(
    process.env.FAUCET_ACCOUNT_PUBLIC_KEY_PATH!,
    process.env.FAUCET_ACCOUNT_PRIVATE_KEY_PATH!);

// Faucet contract and deploy factory.
const faucet = new BoundContract(
  new Contract(process.env.FAUCET_CONTRACT_PATH!),
  contractKeys, process.env.FAUCET_NONCE_PATH!);

// gRPC client to the node.
const deployService = new DeployService(process.env.CASPER_SERVICE_URL!);

const app = express();

// Support using the faucet in offline mode with the mock.
const isMock = process.env.AUTH_MOCK_ENABLED === "true";

// create the JWT middleware
const checkJwt: express.RequestHandler = isMock ?
  (req, res, next) => {
    const token = req.headers.authorization;
    if (token && token.startsWith("Bearer mock|")) {
      (req as any).user = { sub: token.substr(7) };
    } else if (token) {
      return res.status(401).send({ msg: "Expected the mock token only." });
    }
    return next();
  }
  : jwt({
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

// Render the `config.js` file dynamically.
app.get("/config.js", (_, res) => {
  const conf = {
    auth0: config.auth0,
    auth: {
      mock: {
        enabled: isMock
      }
    },
    grpc: {
      // In production we can leave this empty and then it should
      // connect to window.origin and be handled by nginx.
      url: process.env.UI_GRPC_URL
    }
  };
  res.header("Content-Type", "application/javascript");
  res.send(`var config = ${JSON.stringify(conf, null, 2)};`);
});

// Serve the static files of the UI
// Serve the static files of the UI.
// STATIC_ROOT needs to be an absolute path, otherwise we assume we'll use the `ui` projec's build output.
const staticRoot = path.isAbsolute(process.env.STATIC_ROOT!)
  ? process.env.STATIC_ROOT!
  : path.join(__dirname, process.env.STATIC_ROOT!);

app.use(express.static(staticRoot));

app.get("/", (_, res) => {
  res.sendFile(path.join(staticRoot, "index.html"));
});

// Parse JSON sent in the body.
app.use(express.json());

// Faucet endpoint.
app.post("/api/faucet", checkJwt, (req, res) => {
  // express-jwt put the token in res.user
  // const userId = (req as any).user.sub;
  const accountPublicKeyBase64 = req.body.accountPublicKeyBase64 || "";
  if (accountPublicKeyBase64 === "") {
    throw Error("The 'accountPublicKeyBase64' is missing.");
  }

  // Prepare the signed deploy.
  const accountPublicKey = decodeBase64(accountPublicKeyBase64);
  const deploy = faucet.deploy(Faucet.args(accountPublicKey));

  // Send the deploy to the node and return the deploy hash to the browser.
  deployService
    .deploy(deploy)
    .then(() => {
      const response = {
        deployHashBase16: Buffer.from(deploy.getDeployHash_asU8()).toString("hex")
      };
      res.send(response);
    })
    .catch((err) => {
      // TODO: Rollback nonce?
      const msg = err.toString();
      // The service already logged it.
      res.status(500).send({ error: msg });
    });
});

// Error report in JSON.
app.use((err: any, req: any, res: any, next: any) => {
  console.log("ERROR", req.path, err);
  if (err.name === "UnauthorizedError") {
    return res.status(401).send({ msg: "Invalid token" });
  }
  if (req.path === "/api/faucet") {
    return res.status(500).send({ error: err.toString() });
  }
  next(err, req, res);
});

// start the express server
if (process.env.SERVER_USE_TLS === "true") {
  const cert = process.env.SERVER_TLS_CERT_PATH!;
  const key = process.env.SERVER_TLS_KEY_PATH!;
  https.createServer({
    key: fs.readFileSync(key),
    cert: fs.readFileSync(cert),
  }, app)
    .listen(port, () => {
      // tslint:disable-next-line:no-console
      console.log(`server started at https://localhost:${port}`);
    });
} else {
  app.listen(port, () => {
    // tslint:disable-next-line:no-console
    console.log(`server started at http://localhost:${port}`);
  });
}
