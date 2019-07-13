import blake from "blakejs";
import fs from "fs";
import { Message } from "google-protobuf";
import * as nacl from "tweetnacl-ts";
import { Approval, Deploy, Signature } from "../grpc/io/casperlabs/casper/consensus/consensus_pb";
import { Args, PublicKeyArg, UInt64Arg } from "../shared/Serialization";

// https://www.npmjs.com/package/tweetnacl-ts
// https://github.com/dcposch/blakejs

export function byteHash(x: ByteArray): ByteArray {
  return blake.blake2b(x, null, 32);
}

export function protoHash<T extends Message>(x: T): ByteArray {
  return byteHash(x.serializeBinary());
}

export class Contract {
  private contractWasm: ByteArray;

  constructor(contractPath: string) {
    this.contractWasm = fs.readFileSync(contractPath);
  }

  public deploy(
    args: ByteArray,
    nonce: number,
    accountPublicKey: ByteArray,
    signingKeyPair: nacl.SignKeyPair): Deploy {

    const code = new Deploy.Code();
    code.setCode(this.contractWasm);
    code.setArgs(args);

    const body = new Deploy.Body();
    body.setSession(code);
    body.setPayment(code);

    const header = new Deploy.Header();
    header.setAccountPublicKey(accountPublicKey);
    header.setNonce(nonce);
    header.setTimestamp(new Date().getTime());
    header.setBodyHash(protoHash(body));

    const deploy = new Deploy();
    deploy.setBody(body);
    deploy.setHeader(header);
    deploy.setDeployHash(protoHash(header));

    const signature = new Signature();
    signature.setSigAlgorithm("ed25519");
    signature.setSig(nacl.sign(deploy.getDeployHash_asU8(), signingKeyPair.secretKey));

    const approval = new Approval();
    approval.setApproverPublicKey(signingKeyPair.publicKey);
    approval.setSignature(signature);

    deploy.addApprovals(approval);

    return deploy;
  }
}

/** Always use the same account for deploying and signing, and keep the nonce persisted in a file. */
export class BoundContract {
  private noncePath: string;
  private nonce: number;

  constructor(
    private contract: Contract,
    private contractKeyPair: nacl.SignKeyPair,
    noncePath: string) {
    this.noncePath = noncePath;
    this.initNonce();
  }

  public deploy(args: ByteArray): Deploy {
    return this.contract.deploy(
      args, this.nextNonce(),
      this.contractKeyPair.publicKey,
      this.contractKeyPair);
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

export class Faucet {
  public static args(accountPublicKey: ByteArray): ByteArray {
    return Args(
      PublicKeyArg(accountPublicKey)
    );
  }
}

export class Transfer {
  public static args(accountPublicKey: ByteArray, amount: bigint): ByteArray {
    return Args(
      PublicKeyArg(accountPublicKey),
      UInt64Arg(amount));
  }
}
