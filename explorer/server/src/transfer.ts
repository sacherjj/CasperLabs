import commandLineArgs from "command-line-args";
import { Contract, Transfer } from "./lib/Contracts";
import { Ed25519 } from "./lib/Keys";
import DeployService from "./services/DeployService";

// https://www.npmjs.com/package/command-line-args

const optionDefinitions = [
  { name: "host-url", type: String },
  { name: "transfer-contract-path", type: String },
  { name: "payment-contract-path", type: String },
  { name: "from-public-key-path", type: String },
  { name: "from-private-key-path", type: String },
  { name: "to-public-key-path", type: String },
  { name: "amount", type: BigInt },
  { name: "nonce", type: Number },
  { name: "payment-amount", type: BigInt },
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

const hex = (x: ByteArray) => Buffer.from(x).toString("hex");

const accountPublicKey = Ed25519.parsePublicKeyFile(options["to-public-key-path"]);
const accountPublicKeyBase16 = hex(accountPublicKey);

const transfer = new Contract(
  options["transfer-contract-path"],
  options["payment-contract-path"]);

const args = Transfer.args(accountPublicKey, options.amount);

const deploy = transfer.deploy(
  args,
  options.nonce,
  options["payment-amount"],
  contractKeys.publicKey,
  contractKeys);

const deployHashBase16 = hex(deploy.getDeployHash_asU8());

console.log(`Transfering tokens to account ${accountPublicKeyBase16}`);
console.log(`Deploying ${deployHashBase16} to ${options["host-url"]}`);

const deployService = new DeployService(options["host-url"]);

deployService.deploy(deploy)
  .then(() => console.log("Done."))
  .catch((err) => { console.log(err); process.exit(1); });
