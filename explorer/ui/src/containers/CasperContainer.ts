import { observable } from 'mobx';

// CasperContainer talks to the API on behalf of React
// components and exposes the state in MobX observables.
export class CasperContainer {

  @observable accounts: Account[] | null = null;

  // We can display the last error when it happens.
  @observable error: string | null = null;

  private exec<T>(p: Promise<T>, f: (x: T) => void) {
    p.then(f).catch(err => {
      this.error = err.message;
    });
  }

  refreshAccounts() {
    // this.exec(this.service.listAccounts(), (xs) => {
    //   this.accounts = xs;
    // })
  }
}

export default CasperContainer;
