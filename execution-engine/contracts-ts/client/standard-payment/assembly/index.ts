import * as CL from "../../../../contract-ffi-ts/assembly";

export function call(): void {
  var amountBytes = CL.getArg(0);
  if (amountBytes == null) {
    CL.revert(1);
    return;
  }

  var mainPurse = CL.getMainPurse();
  if (mainPurse == null) {
    CL.revert(2);
    return;
  }

  var proofOfStake = CL.getSystemContract(CL.SystemContract.ProofOfStake);
  if (proofOfStake == null) {
    CL.revert(3);
    return;
  }

  var key = CL.Key.fromURef(<CL.URef>proofOfStake);
  var output = CL.callContract(key, [
    CL.CLValue.fromString("get_payment_purse"),
  ]);

  if (output == null) {
    CL.revert(4);
    return;
  }

  var paymentPurse = CL.URef.fromBytes(output);
  if (paymentPurse == null) {
    CL.revert(5);
  }

  var ret = CL.transferFromPurseToPurse(
    mainPurse,
    <CL.URef>(paymentPurse),
    amountBytes,
  );
  if (ret > 0) {
    CL.revert(6);
  }
}
