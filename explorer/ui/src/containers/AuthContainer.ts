import { observable } from 'mobx';

import { Auth0Provided } from '../react-auth0-wrapper'

export class AuthContainer {

  provided: Auth0Provided;

  constructor(provided: Auth0Provided) {
    this.provided = provided;
  }

  @observable user: User | undefined = undefined;
  @observable isAuthenticated = false

  async login() {
    await this.provided.loginWithPopup();
    this.user = this.provided.user;
    this.isAuthenticated = this.provided.isAuthenticated;
  }

  logout() {
    this.provided.logout();
    this.user = undefined;
    this.isAuthenticated = false;
  }
}

export default AuthContainer;
