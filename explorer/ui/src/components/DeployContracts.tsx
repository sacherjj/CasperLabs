import { observer } from 'mobx-react';
import { FileSelect, Form, FormRow, NumberField, SelectField, TextField } from './Forms';
import { Button, Card, ListInline } from './Utils';
import React from 'react';
import {
  ArgumentType,
  BitWidth,
  ContractType,
  DeployContractsContainer,
  FormDeployArgument,
  KeyType
} from '../containers/DeployContractsContainer';
import Modal from './Modal';
import { Key } from 'casperlabs-grpc/io/casperlabs/casper/consensus/state_pb';


/**
 * The ui component to manage hashes of Vesting Contract,
 * including selecting, adding and removing hashes.
 */
export const DeployContractsForm = observer(
  (props: { deployContractsContainer: DeployContractsContainer }) => {
    let modalAccountForm = (
      <Modal
        id="id-private-key-modal"
        title="Input Private Key"
        submitLabel="Deploy"
        onSubmit={props.deployContractsContainer.onSubmit}
        onClose={() => {
          props.deployContractsContainer.signDeployModal = false;
        }}
      >
        <Form>
          <TextField
            id="id-private-key"
            label="Private Key"
            fieldState={props.deployContractsContainer.privateKey}
            placeholder="Human readable alias"
          />
        </Form>
      </Modal>
    );
    return (
      <Card title="Deploy Smart Contracts" accordionId={props.deployContractsContainer.accordionId}>
        <Form>
          <SelectField id="id-contract-type" label="Type"
                       value={props.deployContractsContainer.deployConfiguration.$.contractType.$}
                       placeholder="Please Select the Type of Deploy"
                       options={
                         Object.keys(ContractType).map(t => {
                           return {
                             label: (ContractType as any)[t],
                             value: t
                           };
                         })}
                       onChange={(value: string) => {
                         props.deployContractsContainer.deployConfiguration.$.contractType.onChange(value as ContractType);
                       }}
          />
          {
            props.deployContractsContainer.deployConfiguration.$.contractType.$ === ContractType.WASM && (
              <FileSelect id="id-wasm-select"
                          label={props.deployContractsContainer.selectedFile?.name || 'Select WASM File'}
                          handleFileSelect={props.deployContractsContainer.handleFileSelect}/>
            )
          }
          {
            props.deployContractsContainer.deployConfiguration.$.contractType.$ === ContractType.Hash && (
              <TextField id="id-contract-hash" label="Hash(Base16) of the Contract"
                         fieldState={props.deployContractsContainer.deployConfiguration.$.contractHash}/>
            )
          }
          <FormRow splits={[6, 6]}>
            <NumberField id="id-gas-price" label="Gas Price"
                         fieldState={props.deployContractsContainer.deployConfiguration.$.gasPrice}/>
            <NumberField id="id-gas-limit" label="Gas Limit"
                         fieldState={props.deployContractsContainer.deployConfiguration.$.gasLimit}/>
          </FormRow>
          <TextField id="id-from-address" label="From (Optional)"
                     fieldState={props.deployContractsContainer.deployConfiguration.$.fromAddress}/>
        </Form>

        {props.deployContractsContainer.signDeployModal && modalAccountForm}

        <Card title="Setting Arguments" accordionId={'arguments-table'}>
          <ArgumentTable deployContractsContainer={props.deployContractsContainer}/>
        </Card>

        <div className="mt-5">
          <ListInline>
            <Button size='lg' onClick={props.deployContractsContainer.openSignModal} title={'Sign'}/>
            <Button size='lg' type='danger' onClick={props.deployContractsContainer.clearForm} title={'Clear'}/>
          </ListInline>
        </div>
      </Card>
    );
  }
);

// const ArgumentTypeSelect = observer((props: {
//   deployArgument: FormDeployArgument
// }) => {
//   return ();
// });

const ArgumentRow = observer((props: {
  infix: string,
  deployArgument: FormDeployArgument,
  onProductTableUpdate: () => void,
  onDelEvent?: (deployArgument: FormDeployArgument) => void,
}) => {
  return (
    <tr>
      <td>
        <TextField id={`argument-${props.infix}-name`} fieldState={props.deployArgument.$.name}/>
      </td>
      <td>
        <div className="row">
          <div className="col pl-0 pr-1">
            <select className="form-control" value={props.deployArgument.$.type.$.toString()}
                    onChange={e => {
                      props.deployArgument.$.type.onChange(e.target.value as ArgumentType);
                    }}>
              {
                Object.keys(ArgumentType).map(opt => (
                  <option key={opt} value={(ArgumentType as any)[opt]}>
                    {(ArgumentType as any)[opt]}
                  </option>
                ))
              }
            </select>
          </div>
          {(props.deployArgument.$.type.value === ArgumentType.KEY || props.deployArgument.$.type.value === ArgumentType.BIG_INT) && (
            <div className="col pl-0 pr-1">
              <select className="form-control" value={props.deployArgument.$.secondType.$?.toString()}
                      onChange={e => {
                        if (props.deployArgument.$.type.value === ArgumentType.KEY) {
                          props.deployArgument.$.secondType.onChange(e.target.value as KeyType);
                        } else {
                          props.deployArgument.$.secondType.onChange(e.target.value as unknown as BitWidth);
                        }
                      }}>
                {
                  props.deployArgument.$.type.value === ArgumentType.KEY && (
                    Object.keys(KeyType).map(opt => (
                      <option key={opt} value={(KeyType as any)[opt]}>
                        {(KeyType as any)[opt]}
                      </option>
                    ))
                  ) || (
                    Object.keys(BitWidth).filter(opt => {
                      return typeof (BitWidth as any)[opt] === 'number';
                    }).map(opt => (
                      <option key={opt} value={(BitWidth as any)[opt]}>
                        {(BitWidth as any)[opt]}
                      </option>
                    ))
                  )
                }
              </select>
            </div>
          )}
          {props.deployArgument.$.type.value === ArgumentType.KEY && props.deployArgument.$.secondType.value === KeyType.UREF && (
            <div className="col pl-0 pr-0">
              <select className="form-control" value={props.deployArgument.$.URefAccessRight.$ as number}
                      onChange={e => {
                        props.deployArgument.$.URefAccessRight.onChange(parseInt(e.target.value) as any);
                      }}>
                {
                  Object.keys(Key.URef.AccessRights).map(opt => (
                    <option key={opt} value={(Key.URef.AccessRights as any)[opt]}>
                      {opt}
                    </option>
                  ))
                }
              </select>
            </div>
          )}

        </div>
      </td>
      <td>
        <div>
          <input
            className={`
              form-control
              ${!(props.deployArgument.$.value.hasBeenValidated) ? '' : (props.deployArgument.$.value.hasError || props.deployArgument.hasFormError ? 'is-invalid' : '')}
            `}
            type="text"
            value={props.deployArgument.$.value.value}
            onChange={e => {
              props.deployArgument.$.value.onChange(e.target.value);
            }}
            onBlur={
              () => {
                props.deployArgument.enableAutoValidationAndValidate();
                props.deployArgument.$.value.enableAutoValidationAndValidate();
              }
            }
          />
          {(props.deployArgument.$.value.hasError || props.deployArgument.hasFormError) && (
            <div className="invalid-feedback">
              {props.deployArgument.$.value.error || props.deployArgument.formError}
            </div>
          )}
        </div>
      </td>
      {props.onDelEvent && (
        <td style={{ 'borderTop': 'none' }}>
          <input type="button" className="btn btn-md btn-danger" value="Delete" onClick={() => {
            props.onDelEvent!(props.deployArgument);
          }}/>
        </td>
      )}
    </tr>);
});

const ArgumentTable = observer((props: {
  deployContractsContainer: DeployContractsContainer
}) => (
  <div>
    <table className="table">
      <thead>
      <tr>
        <th style={{ width: '20%' }}>Name</th>
        <th style={{ width: '30%' }}>Type</th>
        <th style={{ width: '40%' }}>Value</th>
      </tr>
      </thead>
      <tbody>
      {
        !props.deployContractsContainer.editing && props.deployContractsContainer.deployArguments.$.length === 0 ? (
          <tr>
            <td>
              No Arguments
            </td>
          </tr>
        ) : (
          props.deployContractsContainer.deployArguments.$.map((deployArgument, idx) => (
            <ArgumentRow key={idx} deployArgument={deployArgument} infix={`saved-${idx}`} onProductTableUpdate={() => {
            }} onDelEvent={props.deployContractsContainer.removeDeployArgument}/>
          ))
        )
      }
      {
        props.deployContractsContainer.editing && props.deployContractsContainer.editingDeployArguments.$.map((deployArgument, idx) => (
          <ArgumentRow key={idx} infix={`editing-${idx}`} deployArgument={deployArgument}
                       onProductTableUpdate={() => {
                       }}/>
        ))
      }
      </tbody>
    </table>
    <div className="mt-3">
      <ul className="list-inline mb-0">
        <li className="list-inline-item">
          <Button onClick={props.deployContractsContainer.addNewEditingDeployArgument} title="Add" size='xs'/>
        </li>
        {props.deployContractsContainer.editing && (
          <li className="list-inline-item float-right mr-5">
            <ListInline>
              <Button onClick={props.deployContractsContainer.cancelEditing} title='cancel' size='xs' type="secondary"/>
              <Button onClick={props.deployContractsContainer.saveEditingDeployArguments} title='save' size='xs'
                      type="success"/>
            </ListInline>
          </li>
        )}
      </ul>
    </div>
  </div>
));
