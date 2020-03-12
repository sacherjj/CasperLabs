import { action, observable } from 'mobx';

import ErrorContainer from './ErrorContainer';
import { CasperService, decodeBase16, decodeBase64, DeployUtil, encodeBase16 } from 'casperlabs-sdk';
import { FieldState, FormState } from 'formstate';
import { isBase16, isInt, numberBigThan, valueRequired } from '../lib/FormsValidator';
import validator from 'validator';
import * as nacl from 'tweetnacl-ts';
import $ from 'jquery';
import { Deploy } from 'casperlabs-grpc/io/casperlabs/casper/consensus/consensus_pb';
import { CLType, CLValueInstance, Key } from 'casperlabs-grpc/io/casperlabs/casper/consensus/state_pb';
import paymentWASMUrl from '../standard_payment.wm';


type SupportedType = CLType.SimpleMap[keyof CLType.SimpleMap] | 'Bytes';

export enum ContractType {
  WASM = 'WASM',
  Hash = 'Hash'
}

export enum KeyType {
  ADDRESS = 'Address',
  HASH = 'Hash',
  UREF = 'URef',
  LOCAL = 'Local'
}

export enum BitWidth {
  B_128 = 128,
  B_256 = 256,
  B_512 = 512
}

const powerOf2 = (n: number): bigint => {
  let res = BigInt(1);
  for (let i = 0; i < n; i++) {
    res *= BigInt(2);
  }
  return res;
};

const numberLimitForUnsigned = (bit: number) => {
  return {
    min: BigInt(0),
    max: powerOf2(bit) - BigInt(1)
  };
};

const numberLimitForSigned = (bit: number) => {
  return {
    min: BigInt(-1) * powerOf2(bit - 1),
    max: powerOf2(bit - 1) - BigInt(1)
  };
};


const NumberLimit = {
  [CLType.Simple.U8]: numberLimitForUnsigned(8),
  [CLType.Simple.U32]: numberLimitForUnsigned(32),
  [CLType.Simple.U64]: numberLimitForUnsigned(64),
  [CLType.Simple.U128]: numberLimitForUnsigned(128),
  [CLType.Simple.U256]: numberLimitForUnsigned(256),
  [CLType.Simple.U512]: numberLimitForUnsigned(512),
  [CLType.Simple.I32]: numberLimitForSigned(32),
  [CLType.Simple.I64]: numberLimitForSigned(64)
};

export type DeployArgument = {
  name: FieldState<string>,
  type: FieldState<SupportedType>,
  // if type == ArgumentType.Key then the type of secondType is KeyType
  // and if type == ArgumentType.BIG_INT, then the type of secondType is BitWidth
  // otherwise second equals to null
  secondType: FieldState<KeyType | BitWidth | null>,
  URefAccessRight: FieldState<Key.URef.AccessRightsMap[keyof Key.URef.AccessRightsMap]>, // null if type != ArgumentType.KEY
  value: FieldState<string>
}

export type FormDeployArgument = FormState<DeployArgument>;
type FormDeployArguments = FormState<FormDeployArgument[]>;

export type DeployConfiguration = {
  contractType: FieldState<ContractType | null>,
  contractHash: FieldState<string>,
  gasPrice: FieldState<number>,
  gasLimit: FieldState<number>,
  fromAddress: FieldState<string>
}

export type FormDeployConfiguration = FormState<DeployConfiguration>;

interface RawDeployArguments {
  name: string,
  type: SupportedType,
  secondType: KeyType | BitWidth | null,
  URefAccessRight: Key.URef.AccessRightsMap[keyof Key.URef.AccessRightsMap],
  value: string
}

interface UserInputPersistent {
  deployConfiguration: {
    contractType: ContractType | null,
    gasPrice: number,
    gasLimit: number,
    fromAddress: string
  },
  deployArguments: RawDeployArguments[],
  editingDeployArguments: RawDeployArguments[],
  editing: boolean
}

export class DeployContractsContainer {
  @observable deployConfiguration: FormDeployConfiguration = new FormState<DeployConfiguration>({
    contractType: new FieldState<ContractType | null>(null).validators(valueRequired),
    contractHash: new FieldState('').disableAutoValidation(),
    gasPrice: new FieldState<number>(10).validators(
      numberBigThan(0),
      isInt
    ),
    gasLimit: new FieldState<number>(10000000).validators(
      numberBigThan(0),
      isInt
    ),
    fromAddress: new FieldState<string>('')
  }).compose().validators(deployConfiguration => {
    if (deployConfiguration.contractType.$ === ContractType.Hash) {
      let value = deployConfiguration.contractHash.value;
      let v = isBase16(value) || valueRequired(value);
      if(v !== false){
        deployConfiguration.contractHash.setError(v);
      }
      return v;
    }else{
      // WASM
      if(!this.selectedFile){
        const msg = "Upload WASM file firstly";
        alert(msg);
        return msg;
      }
    }
    return false;
  });
  @observable deployArguments: FormDeployArguments = new FormState<FormDeployArgument[]>([]);
  @observable editingDeployArguments: FormDeployArguments = new FormState<FormDeployArgument[]>([]);
  @observable privateKey = new FieldState<string>('').validators(valueRequired);
  @observable selectedFile: File | null = null;
  @observable editing: boolean = false;
  @observable signDeployModal: boolean = false;
  // hash of deploy result
  @observable deployedHash: string | null = "3cc0a7764cca3b0a229f32388d920b9fec9f0c0b15df581dc2e957e014409ed5";
  private selectedFileContent: null | ByteArray = null;
  private static PersistentKey = 'deploy-configuration';

  // id for accordion
  accordionId = 'deploy-table-accordion';

  constructor(
    private errors: ErrorContainer,
    private casperService: CasperService
  ) {
    this.tryRestore();
  }

  @action.bound
  removeDeployArgument(deployArgument: FormDeployArgument) {
    let i = this.deployArguments.$.findIndex((f) => f === deployArgument);
    this.deployArguments.$.splice(i, 1);
    this.saveToSessionStore();
  }

  @action.bound
  addNewEditingDeployArgument() {
    this.editing = true;
    this.editingDeployArguments.$.push(this.newDeployArgument());
  }

  private newDeployArgument(
    name: string = '',
    type: SupportedType = CLType.Simple.BOOL,
    secondType: KeyType | BitWidth | null = null,
    accessRight: Key.URef.AccessRightsMap[keyof Key.URef.AccessRightsMap] = Key.URef.AccessRights.NONE,
    value: string = ''
  ) {
    return new FormState({
      name: new FieldState<string>(name).disableAutoValidation().validators(valueRequired),
      type: new FieldState<SupportedType>(type),
      secondType: new FieldState<KeyType | BitWidth | null>(secondType),
      URefAccessRight: new FieldState<Key.URef.AccessRightsMap[keyof Key.URef.AccessRightsMap]>(accessRight),
      value: new FieldState<string>(value).disableAutoValidation().validators(valueRequired)
    }).compose().validators(this.validateDeployArgument);
  }

  @action.bound
  handleFileSelect(e: React.ChangeEvent<HTMLInputElement>) {
    if (e.target.files) {
      this.selectedFileContent = null;
      this.selectedFile = e.target.files[0];
      const reader = new FileReader();
      reader.readAsArrayBuffer(this.selectedFile);
      reader.onload = _ => {
        this.selectedFileContent = new Uint8Array(reader.result as ArrayBuffer);
      };
    }
  }

  @action.bound
  async saveEditingDeployArguments() {
    const res = await this.editingDeployArguments.validate();
    if (!res.hasError) {
      while (this.editingDeployArguments.$.length) {
        this.deployArguments.$.push(this.editingDeployArguments.$.shift()!);
      }
      this.editing = false;
    }
    // persist manually
    this.saveToSessionStore();
  }

  @action.bound
  cancelEditing() {
    this.editingDeployArguments.$.splice(0, this.editingDeployArguments.$.length);
    this.editing = false;
    this.saveToSessionStore();
  }

  @action.bound
  clearForm() {
    let msg = 'Do you want to clear the form?';
    if (window.confirm(msg)) {
      this.deployConfiguration.reset();
      this.editing = false;
      this.editingDeployArguments.reset();
      this.deployArguments.reset();
    }
  }


  @action.bound
  async openSignModal() {
    let v1 = await this.deployConfiguration.validate();
    let v2 = await this.deployArguments.validate();
    if (v1.hasError || v2.hasError) {
      return;
    } else {
      this.signDeployModal = true;
    }
  }

  @action.bound
  async onSubmit() {
    this.deployedHash = null;
    let privateKey = decodeBase64(this.privateKey.$);
    let keyPair = nacl.sign_keyPair_fromSecretKey(privateKey);
    let deploy = await this.makeDeploy(keyPair.publicKey);
    let signedDeploy = DeployUtil.signDeploy(deploy!, keyPair);
    try {
      await this.errors.withCapture(this.casperService.deploy(signedDeploy));
      ($(`#${this.accordionId}`) as any).collapse('hide');
      this.deployedHash = encodeBase16(signedDeploy.getDeployHash_asU8());
      return true;
    } catch {
      return true;
    }
  }

  private async makeDeploy(publicKey: Uint8Array): Promise<Deploy | null> {
    let deployConfigurationForm = await this.deployConfiguration.validate();
    let deployArguments = await this.deployArguments.validate();
    if (deployConfigurationForm.hasError || deployArguments.hasError) {
      return null;
    } else {
      const config = deployConfigurationForm.value;
      const args = deployArguments.value;
      let type: 'Hash' | 'WASM';
      let session: ByteArray;
      if (config.contractType.value === ContractType.Hash) {
        type = 'Hash';
        session = decodeBase16(config.contractHash.value);
      } else {
        type = 'WASM';
        session = this.selectedFileContent!;
      }
      const gasLimit = config.gasLimit.value;
      const gasPrice = config.gasPrice.value;
      let argsProto = args.map((arg: FormState<DeployArgument>) => {
        const value = new CLValueInstance.Value();
        const argValueStr: string = arg.$.value.value;
        const type = arg.$.type.$;
        const clType = new CLType();
        if (type === 'Bytes') {
          const innerType = new CLType();
          innerType.setSimpleType(CLType.Simple.U8);
          const fixedListType = new CLType.FixedList();
          fixedListType.setInner(innerType);
          const bytes = decodeBase16(argValueStr);
          fixedListType.setLen(bytes.length);
          clType.setFixedListType(fixedListType);
          value.setBytesValue(bytes);
        } else {
          const simpleType = type as CLType.SimpleMap[keyof CLType.SimpleMap];
          switch (simpleType) {
            case CLType.Simple.U8:
              value.setU8(parseInt(argValueStr));
              break;
            case CLType.Simple.U32:
              value.setU32(parseInt(argValueStr));
              break;
            case CLType.Simple.U64:
              value.setU64(parseInt(argValueStr));
              break;
            case CLType.Simple.I32:
              value.setI32(parseInt(argValueStr));
              break;
            case CLType.Simple.I64:
              value.setI64(parseInt(argValueStr));
              break;
            case CLType.Simple.U128:
              const u128 = new CLValueInstance.U128();
              u128.setValue(argValueStr);
              value.setU128(u128);
              break;
            case CLType.Simple.U256:
              const u256 = new CLValueInstance.U256();
              u256.setValue(argValueStr);
              value.setU256(u256);
              break;
            case CLType.Simple.U512:
              const u512 = new CLValueInstance.U512();
              u512.setValue(argValueStr);
              value.setU256(u512);
              break;
            case CLType.Simple.STRING:
              value.setStrValue(argValueStr);
              break;
            case CLType.Simple.BOOL:
              value.setBoolValue(validator.toBoolean(argValueStr));
              break;
            case CLType.Simple.KEY:
              const key = new Key();
              let keyType = arg.$.secondType.value as KeyType;
              let valueInByteArray = decodeBase16(argValueStr);
              switch (keyType) {
                case KeyType.ADDRESS:
                  const address = new Key.Address();
                  address.setAccount(valueInByteArray);
                  key.setAddress(address);
                  break;
                case KeyType.HASH:
                  const hash = new Key.Hash();
                  hash.setHash(valueInByteArray);
                  key.setHash(hash);
                  break;
                case KeyType.UREF:
                  const URef = new Key.URef();
                  URef.setUref(valueInByteArray);
                  URef.setAccessRights(arg.$.URefAccessRight.value!);
                  break;
                case KeyType.LOCAL:
                  const local = new Key.Local();
                  local.setHash(valueInByteArray);
                  break;
              }
              value.setKey(key);
              break;
            case CLType.Simple.UREF:
              const URef = new Key.URef();
              URef.setAccessRights(arg.$.URefAccessRight.value!);
              URef.setUref(decodeBase16(argValueStr));
              value.setUref(URef);
              break;
          }
          clType.setSimpleType(simpleType);
        }

        const clValueInstance = new CLValueInstance();
        clValueInstance.setClType(clType);
        clValueInstance.setValue(value);

        const deployArg = new Deploy.Arg();
        deployArg.setName(arg.$.name.value);
        deployArg.setValue(clValueInstance);
        return deployArg;
      });
      let wasmRequest = await fetch(paymentWASMUrl);
      let paymentWASM: ArrayBuffer = await wasmRequest.arrayBuffer();
      return DeployUtil.makeDeploy(argsProto, type, session, new Uint8Array(paymentWASM), BigInt(gasLimit), publicKey, gasPrice);
    }
  }


  private validateDeployArgument(deployArgument: DeployArgument): string | false {
    const value = deployArgument.value.$;
    switch (deployArgument.type.$) {
      case CLType.Simple.U8:
      case CLType.Simple.U32:
      case CLType.Simple.U64:
      case CLType.Simple.I32:
      case CLType.Simple.I64:
      case CLType.Simple.U128:
      case CLType.Simple.U256:
      case CLType.Simple.U512:
        let limit: { min: bigint, max: bigint } = (NumberLimit as any)[deployArgument.type.value];
        if (!validator.isNumeric(value)) {
          return `Value should be a number`;
        }
        const v = BigInt(value);
        if (v < limit.min || v > limit.max) {
          return `Value should be in [${limit.min.toString(10)}, ${limit.max.toString(10)}]`;
        }
        return false;
      case CLType.Simple.STRING:
        return false;
      case CLType.Simple.BOOL:
        if (!validator.isBoolean(value)) {
          return `Value should be true or false`;
        }
        return false;
      case CLType.Simple.KEY:
      case CLType.Simple.UREF:
        if (!validator.isHexadecimal(value)) {
          return `Value should be base16`;
        }
        return false;
      case 'Bytes':
        if (!validator.isHexadecimal(value)) {
          return `Value should be base16`;
        }
        return false;
    }
    return false;
  }

  @action
  private tryRestore() {
    const preState = sessionStorage.getItem(DeployContractsContainer.PersistentKey);
    if (preState !== null) {
      const value = JSON.parse(preState) as UserInputPersistent;

      this.editing = value.editing;

      this.deployConfiguration.$.gasLimit.onChange(value.deployConfiguration.gasLimit);
      this.deployConfiguration.$.gasPrice.onChange(value.deployConfiguration.gasPrice);
      this.deployConfiguration.$.contractType.onChange(value.deployConfiguration.contractType);
      this.deployConfiguration.$.fromAddress.onChange(value.deployConfiguration.fromAddress);

      this.editingDeployArguments.reset();
      this.deployArguments.reset();

      value.editingDeployArguments?.forEach(arg => {
        const deployArgument = this.newDeployArgument(arg.name, arg.type, arg.secondType, arg.URefAccessRight, arg.value);
        this.editingDeployArguments.$.push(deployArgument);
      });

      value.deployArguments?.forEach(arg => {
        const deployArgument = this.newDeployArgument(arg.name, arg.type, arg.secondType, arg.URefAccessRight, arg.value);
        this.deployArguments.$.push(deployArgument);
      });
    }
  }

  public saveToSessionStore() {
    let deployConfiguration = this.deployConfiguration.$;
    let state: UserInputPersistent = {
      deployArguments: this.deployArguments.$.map(arg => {
        const value = arg.$;
        return {
          name: value.name.value,
          type: value.type.value,
          secondType: value.secondType.value,
          URefAccessRight: value.URefAccessRight.value,
          value: value.value.value
        };
      }),
      deployConfiguration: {
        contractType: deployConfiguration.contractType.value,
        gasPrice: deployConfiguration.gasPrice.value,
        gasLimit: deployConfiguration.gasLimit.value,
        fromAddress: deployConfiguration.fromAddress.value
      },
      editing: this.editing,
      editingDeployArguments: this.editingDeployArguments.$.map(arg => {
        const value = arg.$;
        return {
          name: value.name.value,
          type: value.type.value,
          secondType: value.secondType.value,
          URefAccessRight: value.URefAccessRight.value,
          value: value.value.value
        };
      })
    };

    sessionStorage.setItem(DeployContractsContainer.PersistentKey, JSON.stringify(state));
  }
}
