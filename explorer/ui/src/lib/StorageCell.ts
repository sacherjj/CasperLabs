import { observable, computed } from 'mobx';

// Store data in session storage.
export default class StorageCell<T> {
  @observable private _value: T;

  constructor(private key: string, defaultValue: T) {
    const existing = sessionStorage.getItem(key);
    if (existing != null) {
      this._value = JSON.parse(existing);
    } else {
      this._value = defaultValue;
      this.save();
    }
  }

  private save() {
    sessionStorage.setItem(this.key, JSON.stringify(this._value));
  }

  @computed get get() {
    return this._value;
  }

  set(v: T) {
    this._value = v;
    this.save();
  }
}
