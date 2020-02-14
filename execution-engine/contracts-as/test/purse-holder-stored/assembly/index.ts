//@ts-nocheck
import * as CL from "../../../../contract-as/assembly";
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {fromBytesString, toBytesMap} from "../../../../contract-as/assembly/bytesrepr";
import {Key} from "../../../../contract-as/assembly/key";
import {putKey, ret} from "../../../../contract-as/assembly";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {createPurse} from "../../../../contract-as/assembly/purse";
import {URef} from "../../../../contract-as/assembly/uref";

const ENTRY_FUNCTION_NAME = "delegate";
const CONTRACT_NAME = "purse_holder_stored";
const METHOD_ADD = "add";
const METHOD_VERSION = "version";
const VERSION = "1.0.0";

enum Args {
  MethodName = 0,
  PurseName = 1,
}

enum CustomError {
  MissingMethodNameArg = 0,
  InvalidMethodNameArg = 1,
  MissingPurseNameArg = 2,
  InvalidPurseNameArg = 3,
  UnknownMethodName = 4,
  NamedPurseNotCreated = 5
}

export function delegate(): void {
  const methodNameArg = CL.getArg(Args.MethodName);
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
    const purseNameArg = CL.getArg(Args.PurseName);
    if (purseNameArg === null){
      Error.fromUserError(<u16>CustomError.MissingPurseNameArg).revert();
      return;
    }
    let purse = createPurse();
    if (purse === null) {
      Error.fromUserError(<u16>CustomError.NamedPurseNotCreated).revert();
      return;
    }
    const uref = <URef>purse;
    const key = Key.fromURef(uref);
    const purseNameResult = fromBytesString(purseNameArg);
    if (purseNameResult.hasError()) {
      Error.fromUserError(<u16>CustomError.InvalidPurseNameArg).revert();
      return;
    }
    let purseName = purseNameResult.value;
    putKey(purseName, <Key>key);
    return;
  }
  if (methodName == METHOD_ADD){
    ret(CLValue.fromString(VERSION));
    return;
  }
  Error.fromUserError(<u16>CustomError.UnknownMethodName).revert();
}

function storeAtURef(): Key {
  let namedKeys = toBytesMap([]);
  return CL.storeFunction(ENTRY_FUNCTION_NAME, namedKeys);
}

export function call(): void {
  let key = storeAtURef();
  putKey(CONTRACT_NAME, key);
  let versionKey = Key.create(CLValue.fromString(VERSION));
  if (versionKey === null){
    Error.fromErrorCode(ErrorCode.MissingKey)
  }
  putKey(METHOD_VERSION, <Key>versionKey);
}
