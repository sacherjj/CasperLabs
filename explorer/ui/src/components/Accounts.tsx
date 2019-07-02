import { observer } from 'mobx-react';

import AuthContainer from '../containers/AuthContainer';
import { RefreshableComponent, UnderConstruction } from './Utils';

interface Props {
  auth: AuthContainer;
}

@observer
export default class Accounts extends RefreshableComponent<Props, {}> {
  refresh() {
    this.props.auth.refreshAccounts();
  }

  render() {
    return UnderConstruction('Grab accounts from Auth0');
  }
}
