import {
  Approval,
  Deploy,
  Signature
} from 'casperlabs-grpc/io/casperlabs/casper/consensus/consensus_pb';
import JSBI from 'jsbi';
import * as nacl from 'tweetnacl-ts';
import { ByteArray } from '../index';
import { Args, BigIntValue } from './Args';
import { protoHash } from './Contracts';

export enum ContractType {
  WASM = 'WASM',
  Hash = 'Hash'
}

// If EE receives a deploy with no payment bytes,
// then it will use host-side functionality equivalent to running the standard payment contract
export const makeDeploy = (
  args: Deploy.Arg[],
  type: ContractType,
  session: ByteArray,
  paymentWasm: ByteArray | null,
  paymentAmount: bigint | JSBI,
  accountPublicKey: ByteArray
): Deploy => {
  const sessionCode = new Deploy.Code();
  if (type === ContractType.WASM) {
    sessionCode.setWasm(session);
  } else {
    sessionCode.setHash(session);
  }
  sessionCode.setArgsList(args);
  if (paymentWasm === null) {
    paymentWasm = Buffer.from('');
  }
  const payment = new Deploy.Code();
  payment.setWasm(paymentWasm);
  payment.setArgsList(Args(['amount', BigIntValue(paymentAmount)]));

  const body = new Deploy.Body();
  body.setSession(sessionCode);
  body.setPayment(payment);

  const header = new Deploy.Header();
  header.setAccountPublicKey(accountPublicKey);
  header.setTimestamp(new Date().getTime());
  header.setBodyHash(protoHash(body));
  // we will remove gasPrice eventually
  header.setGasPrice(1);

  const deploy = new Deploy();
  deploy.setBody(body);
  deploy.setHeader(header);
  deploy.setDeployHash(protoHash(header));
  return deploy;
};

export const signDeploy = (
  deploy: Deploy,
  signingKeyPair: nacl.SignKeyPair
): Deploy => {
  const signature = new Signature();
  signature.setSigAlgorithm('ed25519');
  signature.setSig(
    nacl.sign_detached(deploy.getDeployHash_asU8(), signingKeyPair.secretKey)
  );

  const approval = new Approval();
  approval.setApproverPublicKey(signingKeyPair.publicKey);
  approval.setSignature(signature);

  deploy.setApprovalsList([approval]);

  return deploy;
};

export const setSignature = (
  deploy: Deploy,
  sig: ByteArray,
  publicKey: ByteArray
): Deploy => {
  const signature = new Signature();
  signature.setSigAlgorithm('ed25519');
  signature.setSig(sig);

  const approval = new Approval();
  approval.setApproverPublicKey(publicKey);
  approval.setSignature(signature);

  deploy.setApprovalsList([approval]);

  return deploy;
};
