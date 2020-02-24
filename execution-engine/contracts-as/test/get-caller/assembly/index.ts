import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {typedToArray, checkArraysEqual} from "../../../../contract-as/assembly/utils";
import {PublicKey} from "../../../../contract-as/assembly/key";

export function call(): void {
  const knownPublicKeyBytes = CL.getArg(0);
  if (knownPublicKeyBytes === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }
  let knownPublicKeyResult = PublicKey.fromBytes(knownPublicKeyBytes);
  if (knownPublicKeyResult.hasError()) {
    Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
    return;
  }
  const knownPublicKey = knownPublicKeyResult.value;
  const caller = CL.getCaller();

  assert(caller == knownPublicKey);
}
