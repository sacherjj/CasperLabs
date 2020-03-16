export interface AccountApi {
  unlock(password: string): Promise<void>;
}
