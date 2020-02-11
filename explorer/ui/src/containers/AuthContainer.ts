import { computed, observable } from 'mobx';
import * as nacl from 'tweetnacl-ts';
import { saveAs } from 'file-saver';
import ErrorContainer from './ErrorContainer';
import { AsyncCleanableFormData, CleanableFormData } from './FormData';
import AuthService from '../services/AuthService';
import { BalanceService, CasperService, decodeBase64, encodeBase64, Keys } from 'casperlabs-sdk';
import ObservableValueMap from '../lib/ObservableValueMap';
import { StateQuery } from 'casperlabs-grpc/io/casperlabs/node/api/casper_pb';

// https://www.npmjs.com/package/tweetnacl-ts#signatures
// https://tweetnacl.js.org/#/sign

type AccountB64 = string;

export class AuthContainer {
  @observable user: User | null = null;
  @observable accounts: UserAccount[] | null = null;
  @observable vestingHashes: VestingHash[] | null = null;

  // An account we are creating or importing, while we're configuring it.
  @observable accountForm: NewAccountFormData | ImportAccountFormData | null = null;

  @observable importVestingForm: ImportVestingFormData | null = null;

  @observable selectedAccount: UserAccount | null = null;
  @observable selectedVestingHash: VestingHash | null = null;

  // Balance for each public key.
  @observable balances = new ObservableValueMap<AccountB64, AccountBalance>();

  // How often to query balances. Lots of state queries to get one.
  balanceTtl = 5 * 60 * 1000;

  constructor(
    private errors: ErrorContainer,
    private authService: AuthService,
    private casperService: CasperService,
    private balanceService: BalanceService
  ) {
    this.init();
  }

  private async init() {
    if (window.location.search.includes('code=')) {
      const { appState } = await this.authService.handleRedirectCallback();
      const url =
        appState && appState.targetUrl
          ? appState.targetUrl
          : window.location.pathname;
      window.history.replaceState({}, document.title, url);
    }

    this.fetchUser();
  }

  async login() {
    const isAuthenticated = await this.authService.isAuthenticated();
    if (!isAuthenticated) {
      await this.authService.login();
    }
    this.fetchUser();
  }

  async logout() {
    this.user = null;
    this.accounts = null;
    this.vestingHashes = null;
    this.balances.clear();
    sessionStorage.clear();
    this.authService.logout();
  }

  private async fetchUser() {
    const isAuthenticated = await this.authService.isAuthenticated();
    this.user = isAuthenticated ? await this.authService.getUser() : null;
    this.refreshAccounts();
  }

  async refreshAccounts() {
    if (this.user != null) {
      const meta: UserMetadata = await this.authService.getUserMetadata(
        this.user.sub
      );
      this.accounts = meta.accounts || [];
      this.vestingHashes = meta.vestingHashes || [];
    }
  }

  async refreshBalances(force?: boolean) {
    const now = new Date();
    let latestBlockHash: BlockHash | null = null;

    for (let account of this.accounts || []) {
      const balance = this.balances.get(account.publicKeyBase64);

      const needsUpdate =
        force ||
        balance.value == null ||
        now.getTime() - balance.value.checkedAt.getTime() > this.balanceTtl;

      if (needsUpdate) {
        if (latestBlockHash == null) {
          const latestBlock = await this.casperService.getLatestBlockInfo();
          latestBlockHash = latestBlock.getSummary()!.getBlockHash_asU8();
        }

        const latestAccountBalance = await this.balanceService.getAccountBalance(
          latestBlockHash,
          decodeBase64(account.publicKeyBase64)
        );

        this.balances.set(account.publicKeyBase64, {
          checkedAt: now,
          blockHash: latestBlockHash,
          balance: latestAccountBalance
        });
      }
    }
  }

  // Open a new account creation form.
  configureNewAccount() {
    this.accountForm = new NewAccountFormData(this.accounts!);
  }

  // Open a form for importing account.
  configureImportAccount() {
    this.accountForm = new ImportAccountFormData(this.accounts!);
  }

  // Open a form for importing information of vesting contract
  configureImportVestingHash() {
    this.importVestingForm = new ImportVestingFormData(this.vestingHashes!, this.casperService);
  }

  async createAccount(): Promise<boolean> {
    let form = this.accountForm!;
    if (form instanceof NewAccountFormData && form.clean()) {
      // Save the private and public keys to disk.
      saveToFile(form.privateKeyBase64, `${form.name}.private.key`);
      saveToFile(form.publicKeyBase64, `${form.name}.public.key`);
      // Add the public key to the accounts and save it to Auth0.
      await this.addAccount({
        name: form.name,
        publicKeyBase64: form.publicKeyBase64
      });
      return true;
    } else {
      return false;
    }
  }

  async importAccount(): Promise<boolean> {
    let form = this.accountForm!;
    if (form instanceof ImportAccountFormData && form.clean()) {
      await this.addAccount({
        name: form.name,
        publicKeyBase64: form.publicKeyBase64
      });
      return true;
    } else {
      return false;
    }
  }

  async addVestingItem(): Promise<boolean> {
    let form = this.importVestingForm!;
    let clean = await form.clean();
    if (clean) {
      // Save it to Auth0.
      await this.addVestingHash({
        name: form.name,
        hashBase16: form.hashBase16
      });
      return true;
    } else {
      return false;
    }
  }

  async deleteAccount(name: String) {
    if (window.confirm(`Are you sure you want to delete account '${name}'?`)) {
      this.accounts = this.accounts!.filter(x => x.name !== name);
      await this.errors.capture(this.saveMetaData());
    }
  }

  private async addAccount(account: UserAccount) {
    this.accounts!.push(account);
    await this.errors.capture(this.saveMetaData());
  }

  private async addVestingHash(vestingHash: VestingHash) {
    this.vestingHashes!.push(vestingHash);
    await this.errors.capture(this.saveMetaData());
  }

  async deleteVestingHash(vestingHash: string, msg?: string) {
    msg = msg || `Are you sure you want to delete the stored vesting contract hash '${vestingHash}'?`;
    if (window.confirm(msg)) {
      this.vestingHashes = this.vestingHashes!.filter(x => x.hashBase16 !== vestingHash);
      await this.errors.capture(this.saveMetaData());
    }
  }

  private async saveMetaData() {
    await this.authService.updateUserMetadata(this.user!.sub, {
      accounts: this.accounts || undefined,
      vestingHashes: this.vestingHashes || undefined
    });
  }

  selectAccountByName(name: string) {
    this.selectedAccount = this.accounts!.find(x => x.name === name) || null;
  }

  selectVestingHashByName(name: string) {
    this.selectedVestingHash = this.vestingHashes!.find(x => x.name === name) || null;
  }
}

function saveToFile(content: string, filename: string) {
  let blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
  saveAs(blob, filename);
}

class ImportVestingFormData extends AsyncCleanableFormData {
  @observable name: string = '';
  @observable hashBase16: string = '';

  constructor(private vestingHashes: VestingHash[], private casperService: CasperService) {
    super();
  }

  protected async check() {
    if (this.name === '') return 'Name cannot be empty!';

    if (this.name.indexOf(' ') > -1)
      return 'The account name should not include spaces.';

    if (this.vestingHashes.some(x => x.name === this.name))
      return `An item with name '${this.name}' already exists.`;

    if (this.vestingHashes.some(x => x.hashBase16 === this.hashBase16))
      return 'An item with this contract hash already exists.';


    let stateQuery = new StateQuery();
    stateQuery.setKeyBase16(this.hashBase16);
    stateQuery.setKeyVariant(StateQuery.KeyVariant.HASH);
    stateQuery.setPathSegmentsList(['cliff_timestamp']);
    let lastFinalizedBlockInfo = await this.casperService.getLastFinalizedBlockInfo();
    try {
      await this.casperService.getBlockState(lastFinalizedBlockInfo.getSummary()!.getBlockHash_asU8(), stateQuery);
    } catch (error) {
      return 'Could not find the vesting contract with the hash';
    }
    return null;
  }
}

class AccountFormData extends CleanableFormData {
  @observable name: string = '';
  @observable publicKeyBase64: string = '';

  constructor(private accounts: UserAccount[]) {
    super();
  }

  protected check() {
    if (this.name === '') return 'Name cannot be empty!';

    if (this.name.indexOf(' ') > -1)
      return 'The account name should not include spaces.';

    if (this.accounts.some(x => x.name === this.name))
      return `An account with name '${this.name}' already exists.`;

    if (this.accounts.some(x => x.publicKeyBase64 === this.publicKeyBase64))
      return 'An account with this public key already exists.';

    return null;
  }
}

export class NewAccountFormData extends AccountFormData {
  @observable privateKeyBase64: string = '';

  constructor(accounts: UserAccount[]) {
    super(accounts);
    // Generate key pair and assign to public and private keys.
    const keys = nacl.sign_keyPair();
    this.publicKeyBase64 = encodeBase64(keys.publicKey);
    this.privateKeyBase64 = encodeBase64(keys.secretKey);
  }
}

export class ImportAccountFormData extends AccountFormData {
  @observable file: File | null = null;
  @observable fileContent: string | null = null;
  @observable key: ByteArray | null = null;

  handleFileSelect(e: React.ChangeEvent<HTMLInputElement>) {
    if (e.target.files) {
      this.file = e.target.files[0];
      const reader = new FileReader();
      reader.readAsText(this.file);
      reader.onload = e => {
        this.fileContent = reader.result as string;
        this.error = this.checkFileContent();
        if (this.error === null) {
          this.name = this.fileName!.split('.')[0];
          this.publicKeyBase64 = encodeBase64(this.key!);
        }
      };
    }
  }

  @computed
  get fileName(): string | null {
    if (this.file) {
      return this.file.name;
    }
    return null;
  }

  checkFileContent() {
    if (!this.fileContent) {
      return 'The content of imported file cannot be empty!';
    }
    try {
      this.key = Keys.Ed25519.parsePublicKey(
        Keys.Ed25519.readBase64WithPEM(this.fileContent)
      );
    } catch (e) {
      return e.message;
    }
    return null;
  }

  protected check() {
    let errorMsg = this.checkFileContent();
    if (errorMsg !== null) {
      return errorMsg;
    }
    return super.check();
  }
}

export default AuthContainer;
