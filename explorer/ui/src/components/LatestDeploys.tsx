import React from 'react';
import { observer } from 'mobx-react';
import { ArrowIcon, Button, Card, XIcon } from './Utils';
import { RouteComponentProps, withRouter } from 'react-router';
import Downshift from 'downshift';
import AuthContainer from '../containers/AuthContainer';
import { FaucetContainer } from '../containers/FaucetContainer';
import LatestDeploysContainer from '../containers/LatestDeploysContainer';

interface Props extends RouteComponentProps {
  auth: AuthContainer;
  faucet: FaucetContainer;
  latestDeploysContainer: LatestDeploysContainer;
}

@observer
class _LatestDeploys extends React.Component<Props, {}> {
  constructor(props: Props) {
    super(props);
    this.props.latestDeploysContainer.init(
      this.props.auth.accounts || [],
      this.props.history
    );
  }

  render() {
    let latestDeploysContainer = this.props.latestDeploysContainer;
    return (
      <div>
        <Card title="Search">
          <Downshift
            selectedItem={this.props.latestDeploysContainer.inputValue}
            onStateChange={this.props.latestDeploysContainer.handleStateChange}
          >
            {({
              getInputProps,
              getToggleButtonProps,
              getItemProps,
              isOpen,
              selectedItem,
              inputValue,
              highlightedIndex
            }) => (
              <div>
                <div style={{ position: 'relative' }}>
                  <input
                    {...getInputProps({
                      placeholder: 'Enter Account Public Key',
                      className: 'form-control'
                    })}
                  />
                  {selectedItem ? (
                    <button
                      onClick={latestDeploysContainer.clearSelection}
                      className="controller-button"
                      aria-label="clear selection"
                    >
                      <XIcon />
                    </button>
                  ) : (
                    <button
                      className="controller-button"
                      {...getToggleButtonProps()}
                    >
                      <ArrowIcon isOpen={isOpen} />
                    </button>
                  )}
                </div>

                <div style={{ position: 'relative' }}>
                  <ul className={`pop-list ${isOpen ? 'is-open' : ''}`}>
                    {isOpen
                      ? latestDeploysContainer
                          .getStringItems(inputValue)
                          .map((item, index) => (
                            <li
                              key={index}
                              className={`option ${
                                highlightedIndex === index ? 'is-active' : ''
                              }`}
                              {...getItemProps({
                                item,
                                index
                              })}
                            >
                              {item}
                            </li>
                          ))
                      : null}
                  </ul>
                </div>
              </div>
            )}
          </Downshift>
          {latestDeploysContainer.checkError && (
            <div className="invalid-feedback">
              {latestDeploysContainer.checkError}
            </div>
          )}
          <div style={{ marginTop: '2em' }}>
            <Button
              title="Submit"
              onClick={() => latestDeploysContainer.submit()}
            />
          </div>
        </Card>
      </div>
    );
  }
}

export const LatestDeploys = withRouter(_LatestDeploys);
export default LatestDeploys;
