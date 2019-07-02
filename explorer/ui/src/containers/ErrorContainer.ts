import { observable } from 'mobx';

export class ErrorContainer {
  // We can display the last error when it happens.
  @observable lastError: string | null = null;

  withCapture<T>(p: Promise<T>, cb: (x: T) => void) {
    p.then(cb).catch(err => {
      this.lastError = err.message;
    });
  }

  dismissLast() {
    this.lastError = null;
  }
}

export default ErrorContainer;
