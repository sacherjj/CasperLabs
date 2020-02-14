//@ts-nocheck
import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {fromBytesString} from "../../../../contract-as/assembly/bytesrepr";
import {Key} from "../../../../contract-as/assembly/key";
import {putKey, removeKey, ret, upgradeContractAtURef} from "../../../../contract-as/assembly";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {URef} from "../../../../contract-as/assembly/uref";
import {createPurse} from "../../../../contract-as/assembly/purse";

const ENTRY_FUNCTION_NAME = "delegate";
const METHOD_ADD = "add";
const METHOD_REMOVE = "remove";
const METHOD_VERSION = "version";
const VERSION = "1.0.1";

enum ApplyArgs {
  MethodName = 0,
  PurseName = 1,
}

enum CallArgs {
  PurseHolderURef = 0,
}

enum CustomError {
  MissingPurseHolderURefArg = 0,
  InvalidPurseHolderURefArg = 1,
  MissingMethodNameArg = 2,
  InvalidMethodNameArg = 3,
  MissingPurseNameArg = 4,
  InvalidPurseNameArg = 5,
  UnknownMethodName = 6,
  UnableToStoreVersion = 7,
  NamedPurseNotCreated = 8
}

export function delegate(): void {
  // methodName
  const methodNameArg = CL.getArg(ApplyArgs.MethodName);
  if (methodNameArg === null) {
    Error.fromUserError(<u16>CustomError.MissingMethodNameArg).revert();
    return;
  }
  const methodNameResult = fromBytesString(methodNameArg);
  if (methodNameResult === null){
    Error.fromUserError(<u16>CustomError.InvalidMethodNameArg).revert();
    return;
  }
  let methodName = methodNameResult.value;
  if (methodName == METHOD_ADD){
    // purseName
    const purseNameArg = CL.getArg(ApplyArgs.PurseName);
    if (purseNameArg === null){
      Error.fromUserError(<u16>CustomError.MissingPurseNameArg).revert();
      return;
    }
    const purseNameResult = fromBytesString(purseNameArg);
    if (purseNameResult === null){
      Error.fromUserError(<u16>CustomError.InvalidPurseNameArg).revert();
      return;
    }
    let purseName = purseNameResult.value;

    let purse = createPurse();
    if (purse === null) {
      Error.fromUserError(<u16>CustomError.NamedPurseNotCreated).revert();
      return;
    }
    const uref = <URef>purse;
    const key = Key.fromURef(uref);
    putKey(purseName, <Key>key);
    return;
  }
  if (methodName == METHOD_REMOVE){
    // purseName
    const purseNameArg = CL.getArg(ApplyArgs.PurseName);
    if (purseNameArg === null){
      Error.fromUserError(<u16>CustomError.MissingPurseNameArg).revert();
      return;
    }
    const purseNameResult = fromBytesString(purseNameArg);
    if (purseNameResult === null){
      Error.fromUserError(<u16>CustomError.InvalidPurseNameArg).revert();
      return;
    }
    let purseName = purseNameResult.value;
    removeKey(purseName);
    return;
  }
  if (methodName == METHOD_ADD){
    ret(CLValue.fromString(VERSION));
    return;
  }
  Error.fromUserError(<u16>CustomError.UnknownMethodName).revert();
}

export function call(): void {
  let urefBytes = CL.getArg(CallArgs.PurseHolderURef);
  if (urefBytes === null) {
    Error.fromUserError(<u16>CustomError.MissingPurseHolderURefArg).revert();
    return;
  }
  let urefResult = URef.fromBytes(urefBytes);
  if (urefResult.hasError()) {
    Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
    return;
  }
  let uref = urefResult.value;
  if (uref.isValid() == false){
    Error.fromUserError(<u16>CustomError.InvalidPurseHolderURefArg).revert();
    return;
  }

  upgradeContractAtURef(ENTRY_FUNCTION_NAME, <URef>uref);

  const maybeVersionKey = Key.create(CLValue.fromString(VERSION));
  if (maybeVersionKey === null) {
    Error.fromUserError(<u16>CustomError.UnableToStoreVersion).revert();
    return;
  }
  const versionKey = <Key>maybeVersionKey;
  putKey(METHOD_VERSION, <Key>versionKey);
}
