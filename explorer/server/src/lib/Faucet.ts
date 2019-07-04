import blake from "blakejs";
import fs from "fs";
import * as nacl from "tweetnacl-ts";
import { decodeBase64 } from "tweetnacl-util";
import { Approval, Deploy, Signature } from "../../../grpc/src/io/casperlabs/casper/consensus/consensus_pb";
import * as Ser from "./Serialization";

// https://www.npmjs.com/package/tweetnacl-ts
// https://github.com/dcposch/blakejs

export default class Faucet {
  private contractWasm: ByteArray;
  private contractKeyPair: nacl.SignKeyPair;
  private noncePath: string;
  private nonce: number;

  constructor(contractPath: string, privateKeyPath: string, noncePath: string) {
    this.contractWasm = fs.readFileSync(contractPath);
    const secretKey = decodeBase64(fs.readFileSync(privateKeyPath).toString());
    this.contractKeyPair = nacl.sign_keyPair_fromSecretKey(secretKey);
    this.noncePath = noncePath;
    this.initNonce();
  }

  public makeArgs(accountPublicKeyBase64: string): ByteArray {
    const accountPublicKey = decodeBase64(accountPublicKeyBase64);
    return Ser.PublicKey(accountPublicKey);
  }

  public makeDeploy(accountPublicKeyBase64: string): Deploy {
    const code = new Deploy.Code();
    code.setCode(this.contractWasm);
    code.setArgs(this.makeArgs(accountPublicKeyBase64));

    const body = new Deploy.Body();
    body.setSession(code);
    body.setPayment(code);

    const header = new Deploy.Header();
    header.setAccountPublicKey(this.contractKeyPair.publicKey);
    header.setNonce(this.nextNonce());
    header.setTimestamp(new Date().getTime());
    header.setBodyHash(blake.blake2b(body.serializeBinary()));

    const deploy = new Deploy();
    deploy.setBody(body);
    deploy.setHeader(header);
    deploy.setDeployHash(blake.blake2b(header.serializeBinary()));

    const signature = new Signature();
    signature.setSigAlgorithm("ed25519");
    signature.setSig(nacl.sign(deploy.getDeployHash_asU8(), this.contractKeyPair.secretKey));

    const approval = new Approval();
    approval.setApproverPublicKey(this.contractKeyPair.publicKey);
    approval.setSignature(signature);

    deploy.addApprovals(approval);

    return deploy;
  }

  private initNonce() {
    if (fs.existsSync(this.noncePath)) {
      this.nonce = Number(fs.readFileSync(this.noncePath).toString());
    } else {
      this.nonce = 1;
    }
  }

  private nextNonce() {
    const nonce = this.nonce++;
    fs.writeFileSync(this.noncePath, this.nonce);
    return nonce;
  }
}
