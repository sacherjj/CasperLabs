import { Dropdown } from 'react-bootstrap';
import React from 'react';
import Pages from './Pages';
import AccountManager from '../container/AccountManager';
import { observer } from 'mobx-react';

interface Props {
  authContainer: AccountManager
}

@observer
export default class Menu extends React.Component<Props, any> {
  render() {
    return this.props.authContainer.hasCreatedVault && this.props.authContainer.isUnLocked && (
      <div id="top-right-menu">
        <Dropdown alignRight={true}>
          <Dropdown.Toggle variant="success" id="dropdown-basic">
          </Dropdown.Toggle>

          <Dropdown.Menu>
            <Dropdown.Item onClick={() => {
              console.log('cc');
            }}>Action</Dropdown.Item>
            <Dropdown.Divider/>
            <Dropdown.Item href={Pages.UnlockPage}>Another action</Dropdown.Item>
            <Dropdown.Item href="#/action-3">Something else</Dropdown.Item>
            <Dropdown.Divider/>
            <Dropdown.Item onClick={() => {
              this.props.authContainer.lock();
            }}>Lock</Dropdown.Item>
          </Dropdown.Menu>
        </Dropdown>
      </div>
    );
  }
}
