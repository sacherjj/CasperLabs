"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const dotenv_1 = __importDefault(require("dotenv"));
const express_1 = __importDefault(require("express"));
const express_jwt_1 = __importDefault(require("express-jwt"));
const jwks_rsa_1 = __importDefault(require("jwks-rsa"));
const path_1 = __importDefault(require("path"));
const config_json_1 = __importDefault(require("./config.json"));
// https://auth0.com/docs/quickstart/spa/vanillajs/02-calling-an-api
// https://github.com/auth0/express-jwt
// initialize configuration
dotenv_1.default.config();
// port is now available to the Node.js runtime
// as if it were an environment variable
const port = process.env.SERVER_PORT;
const app = express_1.default();
// create the JWT middleware
const checkJwt = express_jwt_1.default({
    algorithm: ["RS256"],
    audience: config_json_1.default.auth0.audience,
    issuer: `https://${config_json_1.default.auth0.domain}/`,
    secret: jwks_rsa_1.default.expressJwtSecret({
        cache: true,
        jwksRequestsPerMinute: 5,
        jwksUri: `https://${config_json_1.default.auth0.domain}/.well-known/jwks.json`,
        rateLimit: true,
    }),
});
// Serve the static files of the UI
app.use(express_1.default.static(path_1.default.join(__dirname, "static")));
app.get("/", (_, res) => {
    res.sendFile(path_1.default.join(__dirname, "static", "index.html"));
});
// Faucet endpoint.
app.get("/api/faucet", checkJwt, (_, res) => {
    // express-jwt put the token in res.user
    res.send({
        msg: "Your access token was successfully validated!",
    });
});
// Error report in JSON.
app.use((err, req, res, next) => {
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
//# sourceMappingURL=server.js.map