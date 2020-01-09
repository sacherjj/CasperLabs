import * as CL from "../../../../contract-ffi-as/assembly";
import {Error, ErrorCode} from "../../../../contract-ffi-as/assembly/error";
import {fromBytesString, toBytesMap} from "../../../../contract-ffi-as/assembly/bytesrepr";
import {Key} from "../../../../contract-ffi-as/assembly/key";
import {CLValue} from "../../../../contract-ffi-as/assembly/clvalue";
import {PurseId} from "../../../../contract-ffi-as/assembly/purseid";

const POS_ACTION = "get_payment_purse";
const PAY_FUNCTION_NAME = "pay";

const CONTRACT_NAME = "standard_payment";
const DESTINATION_HASH = "hash";
const DESTINATION_UREF = "uref";

export function delegate(): void {
  let proofOfStake = CL.getSystemContract(CL.SystemContract.ProofOfStake);
  if (proofOfStake == null) {
    Error.fromErrorCode(ErrorCode.InvalidSystemContract).revert();
    return;
  }

  let amountBytes = CL.getArg(0);
  if (amountBytes == null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }

  let mainPurse = PurseId.getMainPurse();
  if (mainPurse == null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }

  let key = proofOfStake.asKey();
  let output = CL.callContract(key, [
    CLValue.fromString(POS_ACTION),
  ]);
  if (output == null) {
    Error.fromErrorCode(ErrorCode.PurseNotCreated).revert();
    return;
  }

  let paymentPurse = PurseId.fromBytes(output);
  if (paymentPurse == null) {
    Error.fromErrorCode(ErrorCode.InvalidPurse).revert();
    return;
  }

  let ret = mainPurse.transferToPurse(
      <PurseId>(paymentPurse),
      amountBytes,
  );
  if (ret > 0) {
    Error.fromErrorCode(ErrorCode.Transfer).revert();
    return;
  }
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
    const unknownDestination = new Error(1);
    unknownDestination.revert();
  }
}
