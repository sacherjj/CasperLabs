import { observable } from 'mobx';

export class ErrorContainer {
  // We can display the last error when it happens.
  @observable lastError: string | null = null;

  capture<T>(p: Promise<T>): Promise<T> {
    return p.catch(err => {
      this.lastError = err.message;
      throw err;
    });
  }

  dismissLast() {
    this.lastError = null;
  }
}

export default ErrorContainer;
