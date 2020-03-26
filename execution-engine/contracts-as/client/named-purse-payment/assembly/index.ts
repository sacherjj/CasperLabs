import * as CL from "../../../../contract-as/assembly";
import {getKey} from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {U512} from "../../../../contract-as/assembly/bignum";
import {fromBytesString} from "../../../../contract-as/assembly/bytesrepr";
import {URef} from "../../../../contract-as/assembly/uref";
import {Key, KeyVariant} from "../../../../contract-as/assembly/key";
import {transferFromPurseToPurse} from "../../../../contract-as/assembly/purse";

const GET_PAYMENT_PURSE = "get_payment_purse";
const SET_REFUND_PURSE= "set_refund_purse";

export function call(): void {
  let proofOfStake = CL.getSystemContract(CL.SystemContract.ProofOfStake);

  let purseNameBytes = CL.getArg(0);
  if (purseNameBytes === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }

  let purseName = fromBytesString(purseNameBytes);
  if (purseName.hasError()) {
    Error.fromErrorCode(ErrorCode.InvalidArgument);
    return;
  }

  let purseKey = getKey(purseName.value);
  if (purseKey === null) {
    Error.fromErrorCode(ErrorCode.InvalidArgument);
    return;
  }

  if (purseKey.variant != KeyVariant.UREF_ID) {
    Error.fromErrorCode(ErrorCode.UnexpectedKeyVariant);
    return;
  }

  let purse = <URef>purseKey.uref;

  let amountBytes = CL.getArg(1);
  if (amountBytes === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }

  let amountResult = U512.fromBytes(amountBytes);
  if (amountResult.hasError()) {
    Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
    return;
  }
  let amount = amountResult.value;

  let proofOfStakeKey = Key.fromURef(proofOfStake);

  // Get Payment Purse
  let paymentPurseOutput = CL.callContract(proofOfStakeKey, [
    CLValue.fromString(GET_PAYMENT_PURSE),
  ]);
  let paymentPurseResult = URef.fromBytes(paymentPurseOutput);
  if (paymentPurseResult.hasError()) {
    Error.fromErrorCode(ErrorCode.InvalidPurse).revert();
    return;
  }
  let paymentPurse = paymentPurseResult.value;

  // Set Refund Purse
  let args: CLValue[] = [CLValue.fromString(SET_REFUND_PURSE), CLValue.fromURef(purse)];
  CL.callContract(proofOfStakeKey, args);

  let ret = transferFromPurseToPurse(
    purse,
    paymentPurse,
    amount,
  );
  if (ret > 0) {
    Error.fromErrorCode(ErrorCode.Transfer).revert();
    return;
  }
}
