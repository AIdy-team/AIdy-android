import AsyncStorage from "@react-native-async-storage/async-storage";

/**
 * Kotlin版 Prefs.kt (SharedPreferences) 相当。AsyncStorageは非同期なので、
 * アプリ起動時に `Prefs.preload()` で全キーを読み込んでおき、以後は同期的な
 * getString/getInt として使えるようにする (ModuleConfig等の同期APIを保つため)。
 */
class PrefsStore {
  private cache = new Map<string, string>();
  private loaded = false;

  async preload(): Promise<void> {
    if (this.loaded) return;
    const keys = await AsyncStorage.getAllKeys();
    const pairs = await AsyncStorage.multiGet(keys);
    for (const [k, v] of pairs) if (v != null) this.cache.set(k, v);
    this.loaded = true;
  }

  getString(key: string, def: string): string {
    return this.cache.get(key) ?? def;
  }

  putString(key: string, value: string): void {
    this.cache.set(key, value);
    void AsyncStorage.setItem(key, value);
  }

  getInt(key: string, def: number): number {
    const v = this.cache.get(key);
    if (v == null) return def;
    const n = parseInt(v, 10);
    return Number.isNaN(n) ? def : n;
  }

  putInt(key: string, value: number): void {
    this.putString(key, String(value));
  }
}

export const Prefs = new PrefsStore();
