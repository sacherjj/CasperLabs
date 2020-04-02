import * as events from 'events';
import { AppState } from '../lib/MemStore';
import { encodeBase64 } from 'tweetnacl-ts';

type SignMessageStatus = 'unsigned' | 'signed' | 'rejected';

export interface SignMessage {
  id: number;
  data: string;
  rawSig?: string;
  time: number;
  status: SignMessageStatus
}

export default class SignMessageManager extends events.EventEmitter {
  private messages: SignMessage[];
  private id: number;

  constructor(private appState: AppState) {
    super();
    this.messages = [];
    this.id = Math.round(Math.random() * Number.MAX_SAFE_INTEGER);
  }

  getUnsignedMsgs() {
    return this.messages.filter((msg) => msg.status === 'unsigned');
  }

  addUnsignedMessageAsync(data: string) {
    return new Promise((resolve, reject) => {
      const msgId = this.addUnsignedMessage(data);
      // await finished
      this.once(`${msgId}:finished`, (data) => {
        switch (data.status) {
          case 'signed':
            console.log('haha signed');
            return resolve(data.rawSig);
          case 'rejected':
            return reject(new Error('User denied message signature.'));
          default:
            return reject(new Error(`MetaMask Message Signature: Unknown problem: ${data.toString()}`));
        }
      });
    });
  }

  getSelectedPublicKey() {
    let pk = this.appState.selectedUserAccount?.signKeyPair.publicKey;
    console.log(pk);
    if (pk) {
      return encodeBase64(pk);
    }
    return undefined;
  }

  // Reject message by msgId
  rejectMsg(msg: SignMessage) {
    this.setMsgStatus(msg.id, 'rejected');
  }

  public setMsgStatusSigned(msgId: number, rawSig: string) {
    const msg = this.getMsg(msgId);
    if (msg === undefined) {
      throw new Error(`Could not find message with id ${msgId}`);
    }
    msg.rawSig = rawSig;
    this.updateMsg(msg);
    this.setMsgStatus(msgId, 'signed');
  }

  private createId() {
    this.id = this.id % Number.MAX_SAFE_INTEGER;
    return this.id++;
  }

  private setMsgStatus(msgId: number, status: SignMessageStatus) {
    const msg = this.getMsg(msgId);
    if (!msg) {
      throw new Error('MessageManager - Message not found for id: "${msgId}".');
    }
    msg.status = status;
    this.updateMsg(msg);
    if (status === 'rejected' || status === 'signed') {
      // fire finished event, so that the Promise can resolve and return result to RPC caller
      this.emit(`${msgId}:finished`, msg);
    }
  }

  private updateMsg(msg: SignMessage) {
    const index = this.messages.findIndex((message) => message.id === msg.id);
    if (index !== -1) {
      this.messages[index] = msg;
    }
    this.updateAppState();
  }

  addUnsignedMessage(data: string) {
    const time = (new Date()).getTime();
    const msgId = this.createId();
    const msgData: SignMessage = {
      id: msgId,
      data,
      time: time,
      status: 'unsigned'
    };
    this.addMsg(msgData);

    return msgId;
  }

  private updateAppState() {
    const unsignedMessages = this.getUnsignedMsgs();
    this.appState.toSignMessages.replace(unsignedMessages);
  }

  private addMsg(msg: SignMessage) {
    this.messages.push(msg);
    this.updateAppState();
  }

  private getMsg(msgId: number): SignMessage | undefined {
    return this.messages.find((msg) => msg.id === msgId);
  }
}
