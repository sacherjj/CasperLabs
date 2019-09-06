import blake from "blakejs";
import fs from "fs";
import { Message } from "google-protobuf";
import * as nacl from "tweetnacl-ts";
import { Approval, Deploy, Signature } from "../grpc/io/casperlabs/casper/consensus/consensus_pb";
import { Args, BigIntValue, BytesValue, LongValue } from "./Args";

// https://www.npmjs.com/package/tweetnacl-ts
// https://github.com/dcposch/blakejs

export function byteHash(x: ByteArray): ByteArray {
  return blake.blake2b(x, null, 32);
}

export function protoHash<T extends Message>(x: T): ByteArray {
  return byteHash(x.serializeBinary());
}

export class Contract {
  private sessionWasm: ByteArray;
  private paymentWasm: ByteArray;

  constructor(sessionPath: string, paymentPath: string) {
    this.sessionWasm = fs.readFileSync(sessionPath);
    this.paymentWasm = fs.readFileSync(paymentPath);
  }

  public deploy(
    args: Deploy.Arg[],
    nonce: number,
    paymentAmount: bigint,
    accountPublicKey: ByteArray,
    signingKeyPair: nacl.SignKeyPair): Deploy {

    const session = new Deploy.Code();
    session.setWasm(this.sessionWasm);
    session.setArgsList(args);

    const payment = new Deploy.Code();
    payment.setWasm(this.paymentWasm);
    payment.setArgsList(Args(["amount", BigIntValue(paymentAmount)]));

    const body = new Deploy.Body();
    body.setSession(session);
    body.setPayment(payment);

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
    signature.setSig(nacl.sign_detached(deploy.getDeployHash_asU8(), signingKeyPair.secretKey));

    const approval = new Approval();
    approval.setApproverPublicKey(signingKeyPair.publicKey);
    approval.setSignature(signature);

    deploy.setApprovalsList([approval]);

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

  public deploy(args: Deploy.Arg[], paymentAmount: bigint): Deploy {
    return this.contract.deploy(
      args, this.nextNonce(),
      paymentAmount,
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
  public static args(accountPublicKey: ByteArray): Deploy.Arg[] {
    return Args(
      ["account", BytesValue(accountPublicKey)]
    );
  }
}

export class Transfer {
  public static args(accountPublicKey: ByteArray, amount: bigint): Deploy.Arg[] {
    return Args(
      ["account", BytesValue(accountPublicKey)],
      ["amount", LongValue(amount)]
    );
  }
}
