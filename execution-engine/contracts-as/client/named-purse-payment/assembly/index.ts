import * as CL from "../../../../contract-as/assembly";
import {getKey} from "../../../../contract-as/assembly";
import {Error, ErrorCode, PosErrorCode} from "../../../../contract-as/assembly/error";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {PurseId} from "../../../../contract-as/assembly/purseid";
import {U512} from "../../../../contract-as/assembly/bignum";
import {fromBytesString} from "../../../../contract-as/assembly/bytesrepr";
import {URef} from "../../../../contract-as/assembly/uref";
import {Key, KeyVariant} from "../../../../contract-as/assembly/key";

const GET_PAYMENT_PURSE = "get_payment_purse";
const SET_REFUND_PURSE= "set_refund_purse";

export function call(): void {
  let proofOfStake = CL.getSystemContract(CL.SystemContract.ProofOfStake);
  if (proofOfStake === null) {
    Error.fromErrorCode(ErrorCode.InvalidSystemContract).revert();
    return;
  }

  let purseNameBytes = CL.getArg(0);
  if (purseNameBytes === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }

  let purseName = fromBytesString(purseNameBytes);
  if (purseName === null) {
    Error.fromErrorCode(ErrorCode.InvalidArgument);
    return;
  }

  let purseKey = getKey(<String>purseName);
  if (purseKey === null) {
    Error.fromErrorCode(ErrorCode.InvalidArgument);
    return;
  }

  if (purseKey.variant != KeyVariant.UREF_ID) {
    Error.fromErrorCode(ErrorCode.UnexpectedKeyVariant);
    return;
  }

  let purseId = new PurseId(<URef>purseKey.uref);

  let amountBytes = CL.getArg(1);
  if (amountBytes === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }

  let amount = U512.fromBytes(amountBytes);
  if (amount === null) {
    Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
    return;
  }

  let proofOfStakeKey = Key.fromURef(proofOfStake);

  // Get Payment Purse
  let paymentPurseOutput = CL.callContract(proofOfStakeKey, [
    CLValue.fromString(GET_PAYMENT_PURSE),
  ]);
  if (paymentPurseOutput === null) {
    Error.fromErrorCode(ErrorCode.PurseNotCreated).revert();
    return;
  }
  let paymentPurse = PurseId.fromBytes(paymentPurseOutput);
  if (paymentPurse === null) {
    Error.fromErrorCode(ErrorCode.InvalidPurse).revert();
    return;
  }

  // Set Refund Purse
  let args: CLValue[] = [CLValue.fromString(SET_REFUND_PURSE), CLValue.fromURef(purseId.asURef())];
  let refundPurseOutput = CL.callContract(proofOfStakeKey, args);
  if (refundPurseOutput === null) {
    Error.fromPosErrorCode(PosErrorCode.RefundPurseKeyUnexpectedType).revert(); // TODO: might not be the correct error code
    return;
  }

  let ret = purseId.transferToPurse(
    <PurseId>(paymentPurse),
    <U512>amount,
  );
  if (ret > 0) {
    Error.fromErrorCode(ErrorCode.Transfer).revert();
    return;
  }
}
