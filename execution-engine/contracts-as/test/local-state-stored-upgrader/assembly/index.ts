//@ts-nocheck
import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {arrayToTyped} from "../../../../contract-as/assembly/utils";
import {fromBytesString, toBytesMap} from "../../../../contract-as/assembly/bytesrepr";
import {readLocal, writeLocal} from "../../../../contract-as/assembly/local";
import {Key} from "../../../../contract-as/assembly/key";
import * as CL from "../../../../contract-as/assembly";
import {putKey, upgradeContractAtURef} from "../../../../contract-as/assembly";
import {URef} from "../../../../contract-as/assembly/uref";

const HELLO_PREFIX = " Hello, ";
const WORLD_SUFFIX = "world!";

const ENTRY_FUNCTION_NAME = "delegate";
const SNIPPET = " I've been upgraded!";

enum Args {
  LocalStateURef = 0,
}

enum CustomError {
  MissingURefArg = 1,
  InvalidURefArg = 2,
}

enum DelegatedError {
  FailedFirstReadback = 1,
  FailedSecondReadback = 2,
  FailedThirdReadback = 3,
  FailedFinalReadback = 4
}

export function delegate(): void {
  const localBytes = new Array<u8>(32);
  localBytes.fill(66);
  const local = arrayToTyped(localBytes);

  let maybeValue =  readLocal(local);
  if(maybeValue === null){
    maybeValue = new Uint8Array(0);
  }

  let storedValueResult = fromBytesString(maybeValue);
  let storedValue = storedValueResult.hasError() ? "" : storedValueResult.value;

  writeLocal(local, CLValue.fromString(storedValue + HELLO_PREFIX));

  let readback =  readLocal(local);
  if (readback === null){
    Error.fromUserError(<u16>DelegatedError.FailedFirstReadback).revert()
    return;
  }

  let newValueResult = fromBytesString(readback);
  let newValue = newValueResult.hasError() ? "" : newValueResult.value;

  newValue = newValue + WORLD_SUFFIX;
  writeLocal(local, CLValue.fromString(newValue.trim()));

  readback =  readLocal(local);
  if (readback === null){
    Error.fromUserError(<u16>DelegatedError.FailedSecondReadback).revert()
    return;
  }

  newValueResult = fromBytesString(readback);
  if (newValueResult.hasError()) {
    newValue = "";
  }

  newValue = newValue + SNIPPET;
  writeLocal(local, CLValue.fromString(newValue.trim()));

  readback =  readLocal(local);
  if (readback === null){
    Error.fromUserError(<u16>DelegatedError.FailedThirdReadback).revert()
    return;
  }

  let finalValue = fromBytesString(readback);

  if (finalValue.hasError()){
    Error.fromUserError(<u16>DelegatedError.FailedFinalReadback).revert()
    return;
  }
}

export function call(): void{
  let urefBytes = CL.getArg(Args.LocalStateURef);
  if (urefBytes === null) {
    Error.fromUserError(<u16>CustomError.MissingURefArg).revert();
    return;
  }
  let urefResult = URef.fromBytes(urefBytes);
  if (urefResult.hasError()) {
    Error.fromErrorCode(ErrorCode.InvalidArgument).revert();
    return;
  }
  let uref = urefResult.value;
  if (uref.isValid() == false){
    Error.fromUserError(<u16>CustomError.InvalidURefArg).revert();
    return;
  }

  upgradeContractAtURef(ENTRY_FUNCTION_NAME, uref);
}
