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

/**
 * Sign Message Manager
 *
 * Algorithm:
 *    1. Injected script call `SignMessageManager.addUnsignedMessageAsync`, we return a Promise, inside the Promise, we will
 *       construct a message and assign it a unique id msgId and then we set up a event listen for `${msgId}:finished`.
 *       Resolve or reject when the event emits.
 *    2. Popup call `SignMessageManager.{rejectMsg|approveMsg}` either to reject or commit the signature request,
 *       and both methods will fire a event `${msgId}:finished`, which is listened by step 1.
 */
export default class SignMessageManager extends events.EventEmitter {
  private messages: SignMessage[];
  private id: number;

  constructor(private appState: AppState) {
    super();
    this.messages = [];
    this.id = Math.round(Math.random() * Number.MAX_SAFE_INTEGER);
  }

  public addUnsignedMessageAsync(data: string) {
    return new Promise((resolve, reject) => {
      const msgId = this.addUnsignedMessage(data);
      // await finished, listen to finish event, which will be fired by `rejectMsg` or `signMsg`.
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

  // return public key of the current selected account
  public getSelectedPublicKey() {
    let pk = this.appState.selectedUserAccount?.signKeyPair.publicKey;
    console.log(pk);
    if (pk) {
      return encodeBase64(pk);
    }
    return undefined;
  }

  // Reject signature request
  public rejectMsg(msg: SignMessage) {
    this.setMsgStatus(msg.id, 'rejected');
  }

  // Approve signature request
  public approveMsg(msgId: number, rawSig: string) {
    const msg = this.getMsg(msgId);
    if (msg === undefined) {
      throw new Error(`Could not find message with id ${msgId}`);
    }
    msg.rawSig = rawSig;
    this.updateMsg(msg);
    this.setMsgStatus(msgId, 'signed');
  }

  private getUnsignedMsgs() {
    return this.messages.filter((msg) => msg.status === 'unsigned');
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

  /**
   * Construct a SignMessage and add it to AppState.toSignMessages
   * @param data
   */
  private addUnsignedMessage(data: string) {
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

  // Update toSignMessage, and it will trigger the autorun in background.ts, and send updated state to Popup
  private updateAppState() {
    const unsignedMessages = this.getUnsignedMsgs();
    this.appState.toSignMessages.replace(unsignedMessages);
  }

  private addMsg(msg: SignMessage) {
    this.messages.push(msg);
    this.updateAppState();
  }

  /**
   * Find msg by msgId
   * @param msgId
   */
  private getMsg(msgId: number): SignMessage | undefined {
    return this.messages.find((msg) => msg.id === msgId);
  }
}
