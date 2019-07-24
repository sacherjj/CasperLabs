import React from 'react';
import ReactDOM from 'react-dom';
import { HashRouter } from 'react-router-dom';

import * as serviceWorker from './serviceWorker';
import App from './components/App';

import 'bootstrap/dist/css/bootstrap.min.css';
import '@fortawesome/fontawesome-free/css/all.min.css';
// https://startbootstrap.com/template-overviews/sb-admin/
import './styles/sb-admin/sb-admin.scss';
import './styles/custom.scss';

// Make `jQuery` available in the window in case any Javascript we import directly uses it.
import * as jQuery from 'jquery';
import * as d3 from 'd3';

import CasperContainer from './containers/CasperContainer';
import AuthContainer from './containers/AuthContainer';
import ErrorContainer from './containers/ErrorContainer';
import FaucetService from './services/FaucetService';
import CasperService from './services/CasperService';
import { Auth0Service, MockAuthService } from './services/AuthService';

let w = window as any;
w.$ = w.jQuery = jQuery;
w.d3 = d3;

const authService = window.config.auth.mock.enabled
  ? new MockAuthService()
  : new Auth0Service(window.config.auth0);
const faucetService = new FaucetService(authService);
const casperService = new CasperService(
  window.config.grpc.url || window.origin
);

const errors = new ErrorContainer();
const auth = new AuthContainer(errors, authService, casperService);
const casper = new CasperContainer(
  errors,
  faucetService,
  casperService,
  // Update the balances when a new faucet request went through.
  () => auth.refreshBalances(true)
);

ReactDOM.render(
  <HashRouter>
    <App casper={casper} auth={auth} errors={errors} />
  </HashRouter>,
  document.getElementById('root')
);

// If you want your app to work offline and load faster, you can change
// unregister() to register() below. Note this comes with some pitfalls.
// Learn more about service workers: https://bit.ly/CRA-PWA
serviceWorker.unregister();
