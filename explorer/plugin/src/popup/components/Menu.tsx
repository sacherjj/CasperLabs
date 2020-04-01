import { Dropdown } from 'react-bootstrap';
import React from 'react';
import Pages from './Pages';
import AccountManager from '../container/AccountManager';
import { observer } from 'mobx-react';
import { Icon } from './Utils';

interface Props {
  authContainer: AccountManager
}

type ButtonProps = React.HTMLProps<HTMLButtonElement>

// The forwardRef is important!!
// Dropdown needs access to the DOM node in order to position the Menu
const CustomToggle = React.forwardRef<HTMLButtonElement, ButtonProps>(({ children, onClick }, ref) => (
  <button
    ref={ref}
    onClick={(e) => {
      e.preventDefault();
      onClick && onClick(e);
    }}
    title={"menu"}
    style={{fontSize: 20}}
    className="link icon-button"
  >
    {children}
    <Icon name="sliders-h"></Icon>
  </button>
));

@observer
export default class Menu extends React.Component<Props, any> {
  render() {
    return this.props.authContainer.hasCreatedVault && this.props.authContainer.isUnLocked && (
      <div id="top-right-menu">
        <Dropdown alignRight={true}>
          <Dropdown.Toggle id="dropdown-basic" as={CustomToggle}/>
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
