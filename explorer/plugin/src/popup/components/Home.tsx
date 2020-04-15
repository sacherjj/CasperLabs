import React from 'react';
import logo from '../img/CasperLabs_Logo_Favicon_RGB_50px.png';
import { Form, TextField } from './Forms';
import { Button } from 'react-bootstrap';
import { Redirect } from 'react-router-dom';
import AccountManager from '../container/AccountManager';
import { HomeContainer } from '../container/HomeContainer';
import { observer } from 'mobx-react';
import Pages from './Pages';
import { LinkButton } from './Utils';
import { RouteComponentProps, withRouter } from 'react-router';

interface Props extends RouteComponentProps {
  authContainer: AccountManager;
  homeContainer: HomeContainer;
}

@observer
class Home extends React.Component<Props, {}> {
  onSubmit() {
    const password = this.props.homeContainer.password.$;
    this.props.authContainer.createNewVault(password);
  }

  renderCreateNewVault() {
    return (
      <div>
        <div className="mt-5 mb-4 text-center">
          <img src={logo} alt="logo" width={120} />
        </div>
        <h2 className="text-center mb-5">Welcome</h2>

        <div>
          <Form
            onSubmit={() => {
              this.onSubmit();
            }}
          >
            <TextField
              label="Set Password"
              type="password"
              placeholder="Password"
              id="set-password"
              fieldState={this.props.homeContainer.password}
            />
            <Button
              disabled={this.props.homeContainer.submitDisabled}
              type="submit"
              block={true}
            >
              Creating
            </Button>
          </Form>
        </div>
      </div>
    );
  }

  renderAccountLists() {
    return (
      <div>
        <div className="mt-5 mb-4 text-center">
          <img src={logo} alt="logo" width={120} />
        </div>
        <h5 className="mt-4 mb-3 text-center">
          You have {this.props.authContainer.userAccounts.length} account key(s)
        </h5>
        <div className="text-center" style={{ marginTop: '100px' }}>
          <LinkButton title="Import Account" path={Pages.ImportAccount} />
        </div>
      </div>
    );
  }

  render() {
    if (this.props.authContainer.hasCreatedVault) {
      if (this.props.authContainer.isUnLocked) {
        if (this.props.authContainer.toSignMessages.length > 0) {
          return <Redirect to={Pages.SignMessage} />;
        } else {
          return this.renderAccountLists();
        }
      } else {
        return <Redirect to={Pages.UnlockPage} />;
      }
    } else {
      return this.renderCreateNewVault();
    }
  }
}

export default withRouter(Home);
