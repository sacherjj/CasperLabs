import commandLineArgs from "command-line-args";
import { Contract, Transfer } from "./lib/Contracts";
import { Ed25519 } from "./lib/Keys";

// https://www.npmjs.com/package/command-line-args

const optionDefinitions = [
  { name: "host-url", type: String },
  { name: "transfer-contract-path", type: String },
  { name: "from-public-key-path", type: String },
  { name: "from-private-key-path", type: String },
  { name: "to-public-key-path", type: String },
  { name: "amount", type: BigInt },
  { name: "nonce", type: Number },
];

const options = commandLineArgs(optionDefinitions);

for (const opt of optionDefinitions) {
  if (typeof options[opt.name] === "undefined") {
    console.log(`'${opt.name}' is missing!`);
    process.exit(1);
  }
}

const contractKeys =
  Ed25519.parseKeyFiles(
    options["from-public-key-path"],
    options["from-private-key-path"]);

const accountPublicKey = Ed25519.parsePublicKeyFile(options["to-public-key-path"]);

const transfer = new Contract(options["transfer-contract-path"]);
const args = Transfer.args(accountPublicKey, options.amount);
const deploy = transfer.deploy(args, options.nonce, contractKeys.publicKey, contractKeys);

console.log(Buffer.from(deploy.getDeployHash_asU8()).toString("hex"));
