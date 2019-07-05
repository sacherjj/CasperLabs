import blake from "blakejs";
import fs from "fs";
import * as nacl from "tweetnacl-ts";
import { Approval, Deploy, Signature } from "../../../grpc/generated/io/casperlabs/casper/consensus/consensus_pb";
import { PublicKey } from "./Serialization";

// https://www.npmjs.com/package/tweetnacl-ts
// https://github.com/dcposch/blakejs

export class Contract {
  private contractWasm: ByteArray;

  constructor(contractPath: string, private contractKeyPair: nacl.SignKeyPair) {
    this.contractWasm = fs.readFileSync(contractPath);
  }

  public deploy(args: ByteArray, nonce: number): Deploy {
    const code = new Deploy.Code();
    code.setCode(this.contractWasm);
    code.setArgs(args);

    const body = new Deploy.Body();
    body.setSession(code);
    body.setPayment(code);

    const header = new Deploy.Header();
    header.setAccountPublicKey(this.contractKeyPair.publicKey);
    header.setNonce(nonce);
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
}

export class FileNonceContract {
  private noncePath: string;
  private nonce: number;

  constructor(private contract: Contract, noncePath: string) {
    this.noncePath = noncePath;
    this.initNonce();
  }

  public deploy(args: ByteArray): Deploy {
    return this.contract.deploy(args, this.nextNonce());
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
    return PublicKey(accountPublicKey);
  }
}

export class Transfer {
  public static args(accountPublicKey: ByteArray, amount: bigint): ByteArray {
    const u64Buffer = Buffer.alloc(8);
    u64Buffer.writeBigUInt64LE(amount);
    return Buffer.concat([PublicKey(accountPublicKey), u64Buffer]);
  }
}
