import * as CL from "../../../../contract-ffi-as/assembly";
import {Error, ErrorCode} from "../../../../contract-ffi-as/assembly/error";
import {fromBytesU64} from "../../../../contract-ffi-as/assembly/bytesrepr";

function compareTwoArrays(lhs: Uint8Array, rhs: Uint8Array): bool {
  // NOTE: Maybe `isArraysEqual` could be moved to API?
  if (lhs.length != rhs.length) {
    return false;
  }

  let result = true;
  for (let i = 0; i < lhs.length; i++) {
    if (lhs[i] != rhs[i]) {
      result = false;
      break;
    }
  }
  return result;
}


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
  assert(compareTwoArrays(knownPublicKey, caller));
}
