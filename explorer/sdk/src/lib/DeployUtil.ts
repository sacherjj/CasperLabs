import { Approval, Deploy, Signature } from 'casperlabs-grpc/io/casperlabs/casper/consensus/consensus_pb';
import * as nacl from 'tweetnacl-ts';
import { ByteArray } from '../index';
import { Args, BigIntValue } from './Args';
import { protoHash } from './Contracts';

export enum ContractType {
  WASM = 'WASM',
  Hash = 'Hash'
}

export const makeDeploy = (
  args: Deploy.Arg[],
  type: ContractType,
  session: ByteArray,
  paymentWasm: ByteArray,
  paymentAmount: bigint,
  accountPublicKey: ByteArray,
  gasPrice: number
): Deploy => {
  const sessionCode = new Deploy.Code();
  if(type === ContractType.WASM){
    sessionCode.setWasm(session);
  }else{
    sessionCode.setHash(session);
  }
  sessionCode.setArgsList(args);
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
  header.setGasPrice(gasPrice);

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
