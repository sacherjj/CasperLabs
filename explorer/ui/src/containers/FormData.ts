import { observable } from 'mobx';

export abstract class CleanableFormData {
  // Assigning to `error` during `clean` will cause
  // the observers to re-render.
  @observable error: string | null = null;

  // Implement to check fields and return an error message
  // or null to indicate success.
  protected abstract check(): string | null;

  clean(): boolean {
    this.error = this.check();
    return this.error == null;
  }
}
