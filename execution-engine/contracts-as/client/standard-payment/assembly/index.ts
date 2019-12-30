import * as CL from "../../../../contract-ffi-as/assembly";

export function call(): void {
  let amountBytes = CL.getArg(0);
  if (amountBytes == null) {
    CL.revert(1);
    return;
  }

  let mainPurse = CL.getMainPurse();
  if (mainPurse == null) {
    CL.revert(2);
    return;
  }

  let proofOfStake = CL.getSystemContract(CL.SystemContract.ProofOfStake);
  if (proofOfStake == null) {
    CL.revert(3);
    return;
  }

  let key = CL.Key.fromURef(<CL.URef>proofOfStake);
  let output = CL.callContract(key, [
    CL.CLValue.fromString("get_payment_purse"),
  ]);
  if (output == null) {
    CL.revert(4);
    return;
  }

  let paymentPurse = CL.URef.fromBytes(output);
  if (paymentPurse == null) {
    CL.revert(5);
  }

  let ret = CL.transferFromPurseToPurse(
    mainPurse,
    <CL.URef>(paymentPurse),
    amountBytes,
  );
  if (ret > 0) {
    CL.revert(6);
  }
}
