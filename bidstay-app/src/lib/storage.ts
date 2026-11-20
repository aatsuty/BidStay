import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

// expo-secure-store has no web implementation, so the browser build uses localStorage.
type WebStorage = { getItem(k: string): string | null; setItem(k: string, v: string): void };
const web = (): WebStorage | undefined => (globalThis as unknown as { localStorage?: WebStorage }).localStorage;

export async function loadItem(key: string): Promise<string | null> {
  try {
    if (Platform.OS === 'web') return web()?.getItem(key) ?? null;
    return await SecureStore.getItemAsync(key);
  } catch {
    return null;
  }
}

export async function saveItem(key: string, value: string): Promise<void> {
  try {
    if (Platform.OS === 'web') web()?.setItem(key, value);
    else await SecureStore.setItemAsync(key, value);
  } catch {
    /* settings are a convenience, never fatal */
  }
}
