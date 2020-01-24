import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {fromBytesU64} from "../../../../contract-as/assembly/bytesrepr";
import {typedToArray, checkArraysEqual} from "../../../../contract-as/assembly/utils";

export function call(): void {
  const knownPublicKey = CL.getArg(0);
  if (knownPublicKey === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }
  if (knownPublicKey.length != 32) {
    Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
    return;
  }
  const caller = CL.getCaller();

  let lhs = typedToArray(knownPublicKey);
  let rhs = typedToArray(caller);

  assert(checkArraysEqual(lhs, rhs));
}
