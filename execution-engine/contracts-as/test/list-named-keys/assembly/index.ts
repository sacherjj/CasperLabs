import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {fromBytesMap,
        fromBytesString,
        toBytesString} from "../../../../contract-as/assembly/bytesrepr";
import {Key} from "../../../../contract-as/assembly/key";
import {Pair} from "../../../../contract-as/assembly/pair";
import {checkItemsEqual, checkArraysEqual, checkTypedArrayEqual} from "../../../../contract-as/assembly/utils";

export function call(): void {
  let expectedInitialNamedKeysBytes = CL.getArg(0);
  if (expectedInitialNamedKeysBytes === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }

  const expectedInitialNamedKeys = fromBytesMap<String, Key>(
    expectedInitialNamedKeysBytes,
    fromBytesString,
    Key.fromBytes);
  if (expectedInitialNamedKeys === null) {
    Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
    return;
  }

  // ===

  let actualNamedKeys = CL.listNamedKeys();
  if (actualNamedKeys === null) {
    Error.fromUserError(<u16>4464).revert();
    return;
  }

  if (!checkItemsEqual(expectedInitialNamedKeys, actualNamedKeys)) {
    Error.fromUserError(<u16>4464 + 41).revert();
  }

  let newNamedKeysBytes = CL.getArg(1);
  if (newNamedKeysBytes === null) {
    Error.fromErrorCode(ErrorCode.MissingArgument).revert();
    return;
  }

  const newNamedKeys = fromBytesMap<String, Key>(
    newNamedKeysBytes,
    fromBytesString,
    Key.fromBytes);

  if (newNamedKeys === null) {
    Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
    return;
  }

  let expectedNamedKeys = expectedInitialNamedKeys;

  for (let i = 0; i < newNamedKeys.length; i++) {
    const namedKey = newNamedKeys[i];
    CL.putKey(namedKey.first, namedKey.second);
    expectedNamedKeys.push(namedKey);

    const actualNamedKeys = CL.listNamedKeys();
    assert(checkItemsEqual(expectedNamedKeys, actualNamedKeys));
  }


  let allKeyNames = new Array<String>();
  for (let i = 0; i < expectedNamedKeys.length; i++) {
    allKeyNames.push(expectedNamedKeys[i].first);
  }

  for (let i = 0; i < allKeyNames.length; i++) {
    CL.removeKey(allKeyNames[i]);

    // TODO: remove on an ordered map, or reconsider giving Map a try with Map.remove
    let removed = false;
    for (let j = 0; j < expectedNamedKeys.length; j++) {
      if (expectedNamedKeys[j].first == allKeyNames[i]) {
        expectedNamedKeys.splice(j, 1);
        removed = true;
        break;
      }
    }

    assert(removed);

    const actualNamedKeys = CL.listNamedKeys();
    assert(checkItemsEqual(expectedNamedKeys, actualNamedKeys));
  }
}
