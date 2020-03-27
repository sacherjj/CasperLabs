import {Error, ErrorCode} from "../../../../contract-as/assembly/error";
import {getKey, callContract, putKey} from "../../../../contract-as/assembly";
import {CLValue} from "../../../../contract-as/assembly/clvalue";
import {Key} from "../../../../contract-as/assembly/key";
import {fromBytesStringList} from "../../../../contract-as/assembly/bytesrepr";
import {Option} from "../../../../contract-as/assembly/option";

const MAIL_FEED_KEY = "mail_feed";
const MAILING_KEY = "mailing";
const PUB_METHOD = "pub";
const SUB_METHOD = "sub";

enum UserError {
    GetKeyNameURef = 0,
    BadSubKey = 1,
    GetMessagesURef = 2,
    FindMessagesURef = 3,
    NoMessages = 4,
    NoSubKey = 5,
}

export function call(): void {
    let contractKey = getKey(MAILING_KEY);
    if (contractKey === null) {
        Error.fromErrorCode(ErrorCode.GetKey).revert();
        return;
    }

    let name = "CasperLabs";
    let maybeSubKeyBytes = callContract(<Key>contractKey, [
        CLValue.fromString(SUB_METHOD),
        CLValue.fromString(name),
    ]);

    const maybeSubKey = Option.fromBytes(<Uint8Array>maybeSubKeyBytes);
    if (maybeSubKey === null || maybeSubKey.isNone()) {
        Error.fromUserError(<u16>UserError.NoSubKey).revert();
        return;
    }

    let subKeyBytes = <Uint8Array>maybeSubKey.unwrap();
    let subKeyResult = Key.fromBytes(subKeyBytes);
    if (subKeyResult.hasError()) {
        Error.fromUserError(<u16>UserError.NoSubKey).revert();
        return;
    }
    let subKey = subKeyResult.unwrap();
    putKey(MAIL_FEED_KEY, subKey);

    let maybeMailFeedKey = getKey(MAIL_FEED_KEY);
    if (maybeMailFeedKey === null) {
        Error.fromUserError(<u16>UserError.GetKeyNameURef).revert();
        return;
    }

    const mailFeedKey = <Key>maybeMailFeedKey;

    if (subKey != mailFeedKey) {
        Error.fromUserError(<u16>UserError.BadSubKey).revert();
        return;
    }

    let message = "Hello, World!";
    callContract(<Key>contractKey, [
        CLValue.fromString(PUB_METHOD),
        CLValue.fromString(message),
    ]);

    const maybeMessagesBytes = subKey.read();
    if (maybeMessagesBytes === null) {
        Error.fromUserError(<u16>UserError.GetMessagesURef)
        return;
    }

    const messageBytes = <Uint8Array>maybeMessagesBytes;
    const messagesResult = fromBytesStringList(messageBytes);
    if (messagesResult.hasError()) {
        Error.fromUserError(<u16>UserError.FindMessagesURef).revert();
        return;
    }
    let messages = messagesResult.value;

    if (messages.length == 0) {
        Error.fromUserError(<u16>UserError.NoMessages).revert();
    }
}
