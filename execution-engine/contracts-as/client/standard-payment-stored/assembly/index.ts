import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {fromBytesString, toBytesMap} from "../../../../contract-as/assembly/bytesrepr";
import {Key} from "../../../../contract-as/assembly/key";
import * as StandardPayment from "../../standard-payment/assembly/index"


const CONTRACT_NAME = "standard_payment";
const DESTINATION_HASH = "hash";
const DESTINATION_UREF = "uref";

export function delegate(): void {
  StandardPayment.call();
}

function storeAtHash(): Key {
  let namedKeys = toBytesMap([]);
  var key = CL.storeFunctionAtHash("delegate", namedKeys);
  if (key === null) {
    Error.fromErrorCode(ErrorCode.UnexpectedKeyVariant).revert();
  }
  return <Key>key;
}

function storeAtURef(): Key {
  let namedKeys = toBytesMap([]);
  return CL.storeFunction("delegate", namedKeys);
}

export function call(): void {
  let destinationBytes = CL.getArg(0);
  if (destinationBytes === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }

  let destination = fromBytesString(destinationBytes);
  if (destination === null) {
    Error.fromErrorCode(ErrorCode.InvalidArgument);
    return;
  }

  if (destination == DESTINATION_HASH) {
    const key = storeAtHash();
    CL.putKey(CONTRACT_NAME, key);
  }
  else if (destination == DESTINATION_UREF) {
    const key = storeAtURef();
    CL.putKey(CONTRACT_NAME, key);
  }
  else {
    const unknownDestination = Error.fromUserError(1);
    unknownDestination.revert();
  }
}
