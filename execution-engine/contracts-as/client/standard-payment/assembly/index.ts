import * as CL from "../../../../contract-ffi-as/assembly";
import {Error, ErrorCode} from "../../../../contract-ffi-as/assembly/error";

const POS_ACTION = "get_payment_purse";

export function call(): void {
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

  let mainPurse = CL.getMainPurse();
  if (mainPurse == null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }

  let key = CL.Key.fromURef(<CL.URef>proofOfStake);
  let output = CL.callContract(key, [
    CL.CLValue.fromString(POS_ACTION),
  ]);
  if (output == null) {
    Error.fromErrorCode(ErrorCode.PurseNotCreated).revert();
    return;
  }

  let paymentPurse = CL.URef.fromBytes(output);
  if (paymentPurse == null) {
    Error.fromErrorCode(ErrorCode.InvalidPurse).revert();
  }

  let ret = CL.transferFromPurseToPurse(
    mainPurse,
    <CL.URef>(paymentPurse),
    amountBytes,
  );
  if (ret > 0) {
    Error.fromErrorCode(ErrorCode.Transfer).revert();
  }
}
