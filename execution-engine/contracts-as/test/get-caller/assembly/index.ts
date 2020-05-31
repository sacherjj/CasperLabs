import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {typedToArray, checkArraysEqual} from "../../../../contract-as/assembly/utils";
import {AccountHash} from "../../../../contract-as/assembly/key";

export function call(): void {
  const knownAssemblyHashBytes = CL.getArg(0);
  if (knownAssemblyHashBytes === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }
  let knownAccountHashResult = AccountHash.fromBytes(knownAssemblyHashBytes);
  if (knownAccountHashResult.hasError()) {
    Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
    return;
  }
  const knownAccountHash = knownAccountHashResult.value;
  const caller = CL.getCaller();

  assert(caller == knownAccountHash);
}
