import {
  getArg,
  getMainPurse,
  getSystemContract,
  SystemContract,
  Key,
  URef,
  revert,
  transferFromPurseToPurse,
  callContract,
  toBytesString,
} from "contract-ffi-ts/assembly/index";

export function call(): void {
  var amountBytes = getArg(0);
  if (amountBytes == null) {
    revert(1);
    return;
  }

  var mainPurse = getMainPurse();
  if (mainPurse == null) {
    revert(2);
    return;
  }

  var proofOfStake = getSystemContract(SystemContract.ProofOfStake);
  if (proofOfStake == null) {
    revert(3);
    return;
  }

  var key = Key.fromURef(<URef>proofOfStake);
  var output = callContract(key, [
    toBytesString("get_payment_purse"),
  ]);

  if (output == null) {
    revert(4);
    return;
  }

  var paymentPurse = URef.fromBytes(output);
  if (paymentPurse == null) {
    revert(5);
  }
  
  var ret = transferFromPurseToPurse(
    mainPurse,
    <URef>(paymentPurse),
    amountBytes,
  );
  if (ret > 0) {
    revert(6);
  }
}
