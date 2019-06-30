import 'bootstrap/dist/js/bootstrap.bundle.min.js';
import $ from 'jquery';
import * as React from 'react';
import { observer } from 'mobx-react';
import { Switch, Route, Link, withRouter } from 'react-router-dom';
import { RouteComponentProps } from 'react-router';

import CasperContainer from '../containers/CasperContainer';

import * as Pages from './Pages';
import Home from './Home';

import logo from '../img/logo-full.png';

// https://medium.com/@pshrmn/a-simple-react-router-v4-tutorial-7f23ff27adf

// MenuItem can define required roles, children, etc.
class MenuItem {
  constructor(
    public path: string,
    public label: string,
    public icon: string,
    public exact: boolean = false
  ) {}
}

const SideMenuItems: MenuItem[] = [
  new MenuItem(Pages.Home, 'Home', 'home', true),
  new MenuItem(Pages.Accounts, 'Accounts', 'address-book'),
  new MenuItem(Pages.Faucet, 'Faucet', 'coins'),
  new MenuItem(Pages.Explorer, 'Explorer', 'project-diagram')
];

export interface AppProps {
  container: CasperContainer;
}

// The entry point for rendering Wharf.
export default class App extends React.Component<AppProps, {}> {
  render() {
    return (
      <div>
        <Navigation {...this.props} />
        <Content {...this.props} />
        <Footer />
      </div>
    );
  }

  componentDidMount() {
    // Initialise the sb-admin components. This is copied from sb-admin.js but
    // that alone couldn't be imported becuase it would run when this component
    // hasn't yet rendered.

    // Configure tooltips for collapsed side navigation (Bootstrap extension)
    // $('.navbar-sidenav [data-toggle="tooltip"]').tooltip({
    //   template: '<div class="tooltip navbar-sidenav-tooltip" role="tooltip"><div class="arrow"></div><div class="tooltip-inner"></div></div>'
    // })

    // Toggle the side navigation
    $('#sidenavToggler').click(function(e) {
      e.preventDefault();
      $('body').toggleClass('sidenav-toggled');
      $('.navbar-sidenav .nav-link-collapse').addClass('collapsed');
      $(
        '.navbar-sidenav .sidenav-second-level, .navbar-sidenav .sidenav-third-level'
      ).removeClass('show');
    });

    // Force the toggled class to be removed when a collapsible nav link is clicked
    $('.navbar-sidenav .nav-link-collapse').click(function(e) {
      e.preventDefault();
      $('body').removeClass('sidenav-toggled');
    });

    // Prevent the content wrapper from scrolling when the fixed side navigation hovered over
    $(
      'body.fixed-nav .navbar-sidenav, body.fixed-nav .sidenav-toggler, body.fixed-nav .navbar-collapse'
    ).on('mousewheel DOMMouseScroll', function(e: any) {
      var e0 = e.originalEvent,
        delta = e0.wheelDelta || -e0.detail;
      this.scrollTop += (delta < 0 ? 1 : -1) * 30;
      e.preventDefault();
    });

    // Scroll to top button appear
    $(document).scroll(function() {
      var scrollDistance = $(this).scrollTop()!;
      if (scrollDistance > 100) {
        $('.scroll-to-top').fadeIn();
      } else {
        $('.scroll-to-top').fadeOut();
      }
    });

    // Scroll to top
    $(document).on('click', 'a.scroll-to-top', function(e) {
      var anchor = $(this);
      var offset = $(anchor.attr('href')!).offset()!;
      $('html, body')
        .stop()
        .animate(
          {
            scrollTop: offset.top
          },
          1000
        );
      e.preventDefault();
    });
  }
}

// NavLink checks whether the current menu is active.
const NavLink = (props: { item: MenuItem }) => {
  let item = props.item;
  // Based on https://github.com/ReactTraining/react-router/blob/master/packages/react-router-dom/modules/NavLink.js
  return (
    <Route
      path={item.path}
      exact={item.exact}
      children={props => {
        const cls = props.match ? 'active' : '';
        return (
          <li
            className={['nav-item', cls].filter(x => x).join(' ')}
            title={item.label}
            data-toggle="tooltip"
            data-placement="right"
          >
            <Link to={item.path} className="nav-link">
              <i className={'fa fa-fw fa-' + item.icon}></i>
              &nbsp;
              <span className="nav-link-text">{item.label}</span>
            </Link>
          </li>
        );
      }}
    />
  );
};

// Render navigation.
// `withRouter` is necessary otherwise the menu links never detect changes:
// https://github.com/ReactTraining/react-router/issues/4781
// https://github.com/mobxjs/mobx-react/issues/210
// https://github.com/mobxjs/mobx-react/issues/274
// Moved `withRouter` to a separate line.
@observer
class _Navigation extends React.Component<
  AppProps & RouteComponentProps<any>,
  {}
> {
  render() {
    let c = this.props.container;
    return (
      <nav
        className="navbar navbar-expand-lg navbar-dark bg-dark fixed-top"
        id="mainNav"
      >
        <a className="navbar-brand" href="https://casperlabs.io/">
          <img src={logo} alt="logo" />
        </a>
        <button
          className="navbar-toggler navbar-toggler-right"
          type="button"
          data-toggle="collapse"
          data-target="#navbarResponsive"
          aria-controls="navbarResponsive"
          aria-expanded="false"
          aria-label="Toggle navigation"
        >
          <span className="navbar-toggler-icon"></span>
        </button>

        <div className="collapse navbar-collapse" id="navbarResponsive">
          {/* Side Bar */}
          <ul className="navbar-nav navbar-sidenav" id="exampleAccordion">
            {SideMenuItems.map(x => (
              <NavLink item={x} key={x.path}></NavLink>
            ))}
          </ul>

          {/* Side Bar Toggle */}
          <ul className="navbar-nav sidenav-toggler">
            <li className="nav-item">
              <a className="nav-link text-center" id="sidenavToggler">
                <i className="fa fa-fw fa-angle-left"></i>
              </a>
            </li>
          </ul>

          <ul className="navbar-nav ml-auto">
            <li className="nav-item">
              <div className="username">
                {c.principal && c.principal.username}
              </div>
            </li>
            <li className="nav-item">
              {(c.principal != null && (
                <a className="nav-link" onClick={_ => c.logout()}>
                  <i className="fa fa-fw fa-sign-out-alt"></i>Sign out
                </a>
              )) || (
                <a className="nav-link" onClick={_ => c.login()}>
                  <i className="fa fa-fw fa-sign-in-alt"></i>Sign in
                </a>
              )}
            </li>
          </ul>
        </div>
      </nav>
    );
  }
}

// If we used a decorator it would keep the Props signature,
// but this way it removes the ReactComponentProps
// so the calling component doesn't have to pass them.
const Navigation = withRouter(_Navigation);

// Render the appropriate page.
const Content = (props: AppProps) => (
  <main>
    <div className="content-wrapper">
      <div className="container-fluid">
        <Alerts {...props} />
        <Switch>
          <Route exact path={Pages.Home} render={_ => <Home {...props} />} />
          <Route path={Pages.Accounts} render={_ => <Home {...props} />} />
          <Route path={Pages.Faucet} render={_ => <Home {...props} />} />
          <Route path={Pages.Explorer} render={_ => <Home {...props} />} />
        </Switch>
      </div>
    </div>
  </main>
);

// Alerts displays the outcome of the last async error on the top of the page.
// Dismissing the error clears the state and removes the element.
const Alerts = observer((props: AppProps) => {
  if (props.container.error == null) return null;
  // Not using the `data-dismiss="alert"` to dismiss via Bootstrap JS
  // becuase then it doesn't re-render when there's a new error.
  return (
    <div id="alert-message">
      <div
        className="alert alert-danger alert-dismissible fade show"
        role="alert"
      >
        <button
          type="button"
          className="close"
          aria-label="Close"
          onClick={_ => (props.container.error = null)}
        >
          <span aria-hidden="true">&times;</span>
        </button>
        <strong>Error!</strong> {props.container.error}
      </div>
    </div>
  );
});

const Footer = () => (
  <section>
    <footer className="sticky-footer">
      <div className="container">
        <div className="text-center">
          <small>
            Get in touch on{' '}
            <a
              href="https://t.me/casperlabs"
              target="_blank"
              rel="noopener noreferrer"
            >
              Telegram
            </a>
          </small>
        </div>
      </div>
    </footer>
    <a className="scroll-to-top rounded" href="#page-top">
      <i className="fa fa-angle-up"></i>
    </a>
  </section>
);
