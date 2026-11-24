import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { Platform } from 'react-native';
import { loadItem, saveItem } from './storage';

export interface GuestSession {
  token: string;
  expiresAt: string;
  guest: { id: number; name?: string; phone: string };
}

export interface SessionState {
  userId: number;
  baseUrl: string;
  auth?: GuestSession;
}

const DEFAULT_URL =
  process.env.EXPO_PUBLIC_API_URL ?? (Platform.OS === 'android' ? 'http://10.0.2.2:8080' : 'http://localhost:8080');

const DEFAULTS: SessionState = { userId: 1, baseUrl: DEFAULT_URL };
const KEY = 'bidstay.session.v1';

interface Ctx extends SessionState {
  update: (patch: Partial<SessionState>) => void;
  reset: () => void;
  cacheKey: string;
}

const SessionContext = createContext<Ctx | null>(null);

export function SessionProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<SessionState>(DEFAULTS);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    loadItem(KEY).then((raw) => {
      if (raw) {
        try {
          const restored: SessionState = { ...DEFAULTS, ...JSON.parse(raw) };
          if (!__DEV__ && restored.baseUrl !== DEFAULT_URL) {
            restored.baseUrl = DEFAULT_URL;
            delete restored.auth;
          }
          if (Platform.OS === 'web' || !restored.auth || Date.parse(restored.auth.expiresAt) <= Date.now()) {
            delete restored.auth;
          }
          setState(restored);
        } catch {
          /* corrupted: fall back to defaults */
        }
      }
      setReady(true);
    });
  }, []);

  const update = useCallback((patch: Partial<SessionState>) => {
    setState((prev) => {
      const next = { ...prev, ...patch };
      if (patch.baseUrl !== undefined && patch.baseUrl !== prev.baseUrl) delete next.auth;
      // The web app keeps bearer tokens in memory. Native settings use SecureStore.
      saveItem(KEY, JSON.stringify(Platform.OS === 'web' ? { ...next, auth: undefined } : next));
      return next;
    });
  }, []);

  const reset = useCallback(() => {
    setState(DEFAULTS);
    saveItem(KEY, JSON.stringify(DEFAULTS));
  }, []);

  const value = useMemo(() => ({
    ...state,
    update,
    reset,
    cacheKey: JSON.stringify([state.baseUrl, state.auth?.guest.id ?? (__DEV__ ? state.userId : null), !!state.auth]),
  }), [state, update, reset]);
  if (!ready) return null;
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): Ctx {
  const ctx = useContext(SessionContext);
  if (!ctx) throw new Error('useSession must be used inside SessionProvider');
  return ctx;
}
