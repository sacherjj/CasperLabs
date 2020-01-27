import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {PurseId} from "../../../../contract-as/assembly/purseid";
import {U512} from "../../../../contract-as/assembly/bignum";
import {Key} from "../../../../contract-as/assembly/key";

const POS_ACTION = "get_payment_purse";

export function call(): void {
  let proofOfStake = CL.getSystemContract(CL.SystemContract.ProofOfStake);
  if (proofOfStake === null) {
    Error.fromErrorCode(ErrorCode.InvalidSystemContract).revert();
    return;
  }

  let amountBytes = CL.getArg(0);
  if (amountBytes === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }

  let amount = U512.fromBytes(amountBytes);
  if (amount === null) {
    Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
    return;
  }

  let mainPurse = PurseId.getMainPurse();
  if (mainPurse === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }

  let key = Key.fromURef(proofOfStake);
  let output = CL.callContract(key, [
    CLValue.fromString(POS_ACTION),
  ]);
  if (output === null) {
    Error.fromErrorCode(ErrorCode.PurseNotCreated).revert();
    return;
  }

  let paymentPurse = PurseId.fromBytes(output);
  if (paymentPurse === null) {
    Error.fromErrorCode(ErrorCode.InvalidPurse).revert();
    return;
  }

  let ret = mainPurse.transferToPurse(
    <PurseId>(paymentPurse),
    amount,
  );
  if (ret > 0) {
    Error.fromErrorCode(ErrorCode.Transfer).revert();
    return;
  }
}
