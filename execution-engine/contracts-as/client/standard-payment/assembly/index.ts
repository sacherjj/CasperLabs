import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {URef} from "../../../../contract-as/assembly/uref";
import {U512} from "../../../../contract-as/assembly/bignum";
import {Key} from "../../../../contract-as/assembly/key";
import {getMainPurse} from "../../../../contract-as/assembly/account";
import {transferFromPurseToPurse} from "../../../../contract-as/assembly/purse";

const POS_ACTION = "get_payment_purse";

export function entryPoint(amount: U512): void {
  let proofOfStake = CL.getSystemContract(CL.SystemContract.ProofOfStake);
  if (proofOfStake === null) {
    Error.fromErrorCode(ErrorCode.InvalidSystemContract).revert();
    return;
  }

  let mainPurse = getMainPurse();
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

  let paymentPurseResult = URef.fromBytes(output);
  if (paymentPurseResult.hasError()) {
    Error.fromErrorCode(ErrorCode.InvalidPurse).revert();
    return;
  }
  let paymentPurse = paymentPurseResult.value;

  let ret = transferFromPurseToPurse(
    mainPurse,
    paymentPurse,
    amount,
  );
  if (ret > 0) {
    Error.fromErrorCode(ErrorCode.Transfer).revert();
    return;
  }
}

export function call(): void {
  let amountBytes = CL.getArg(0);
  if (amountBytes === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }

  let amountResult = U512.fromBytes(amountBytes);
  if (amountResult.hasError()) {
    Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
    return;
  }

  entryPoint(amountResult.value);
}
