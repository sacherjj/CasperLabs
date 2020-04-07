//@ts-nocheck
import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {fromBytesString, toBytesMap} from "../../../../contract-as/assembly/bytesrepr";
import {Key} from "../../../../contract-as/assembly/key";

const CONTRACT_NAME = "do_nothing_stored";
const DESTINATION_HASH = "hash";
const DESTINATION_UREF = "uref";

enum Args {
  Destination = 0,
  PurseName = 1,
}

enum CustomError {
  MissingDestinationArg = 0,
  InvalidDestination = 1,
}

export function delegate(): void {
  // no-op
}

function storeAtHash(): Key {
  let namedKeys = toBytesMap([]);
  var key = CL.storeFunctionAtHash("delegate", namedKeys);
  return key;
}

function storeAtURef(): Key {
  let namedKeys = toBytesMap([]);
  return CL.storeFunction("delegate", namedKeys);
}

export function call(): void {
  let destinationBytes = CL.getArg(Args.Destination);
  if (destinationBytes === null) {
    Error.fromUserError(<u16>CustomError.MissingDestinationArg).revert();
    return;
  }

  let destinationResult = fromBytesString(destinationBytes);
  if (destinationResult.hasError()) {
    Error.fromErrorCode(ErrorCode.InvalidArgument);
    return;
  }
  let destination = destinationResult.value;
  if (destination == DESTINATION_HASH) {
    const key = storeAtHash();
    CL.putKey(CONTRACT_NAME, key);
  }
  else if (destination == DESTINATION_UREF) {
    const key = storeAtURef();
    CL.putKey(CONTRACT_NAME, key);
  }
  else {
    Error.fromUserError(<u16>CustomError.InvalidDestination).revert();
  }
}
