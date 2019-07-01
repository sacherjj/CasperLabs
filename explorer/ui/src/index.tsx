import React from 'react';
import ReactDOM from 'react-dom';
import { HashRouter } from 'react-router-dom';

import * as serviceWorker from './serviceWorker';
import Main from './components/Main';
import CasperContainer from './containers/CasperContainer';
import { Auth0Provider } from "./react-auth0-wrapper";

import 'bootstrap/dist/css/bootstrap.min.css';
import '@fortawesome/fontawesome-free/css/all.min.css';
// https://startbootstrap.com/template-overviews/sb-admin/
import './styles/sb-admin/sb-admin.scss';
import './styles/custom.scss';

// Make `jQuery` available in the window in case any Javascript we import directly uses it.
import * as jQuery from 'jquery';
let w = window as any;
w.$ = w.jQuery = jQuery;

ReactDOM.render(
  <Auth0Provider
    domain={window.config.auth0.domain}
    client_id={window.config.auth0.clientId}
    redirect_uri={window.location.origin}>
    <HashRouter>
      <Main casper={new CasperContainer()} />
    </HashRouter>
  </Auth0Provider>,
  document.getElementById('root')
);

// If you want your app to work offline and load faster, you can change
// unregister() to register() below. Note this comes with some pitfalls.
// Learn more about service workers: https://bit.ly/CRA-PWA
serviceWorker.unregister();
