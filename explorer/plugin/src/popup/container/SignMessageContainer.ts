import { BackgroundManager } from '../BackgroundManager';
import ErrorContainer from '../../../../ui/src/containers/ErrorContainer';
import { AppState } from '../../lib/MemStore';
import { browser } from 'webextension-polyfill-ts';
import { computed } from 'mobx';

class SignMessageContainer {
  constructor(private errors: ErrorContainer, private backgroundManager: BackgroundManager, private appState: AppState) {

  }

  @computed
  get toSignMessage() {
    if (this.appState.toSignMessages.length > 0) {
      return this.appState.toSignMessages[0];
    }
    return null;
  }

  async signMessage() {
    if (!this.toSignMessage) {
      throw new Error('No message to Sign');
    }
    await this.backgroundManager.signMessage(this.toSignMessage);
    this.closeWindow();
  }

  async cancel() {
    if (!this.toSignMessage) {
      throw new Error('No message to Sign');
    }
    await this.backgroundManager.rejectSignMessage(this.toSignMessage);
    this.closeWindow();
  }

  private async closeWindow() {
    let w = await browser.windows.getCurrent();
    if (w.type === 'popup') {
      await browser.windows.remove(w.id!);
    }
  }

}

export default SignMessageContainer;
