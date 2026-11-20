import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { Platform } from 'react-native';
import { loadItem, saveItem } from './storage';

/**
 * AUTH PLACEHOLDER. The backend currently identifies callers by X-User-Id header.
 * When OTP + JWT login lands, this context holds the token instead and the Profile "dev settings" go away.
 */
export interface SessionState {
  userId: number;
  baseUrl: string;
}

const DEFAULT_URL =
  process.env.EXPO_PUBLIC_API_URL ?? (Platform.OS === 'android' ? 'http://10.0.2.2:8080' : 'http://localhost:8080');

const DEFAULTS: SessionState = { userId: 1, baseUrl: DEFAULT_URL };
const KEY = 'bidstay.session.v1';

interface Ctx extends SessionState {
  update: (patch: Partial<SessionState>) => void;
  reset: () => void;
}

const SessionContext = createContext<Ctx | null>(null);

export function SessionProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<SessionState>(DEFAULTS);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    loadItem(KEY).then((raw) => {
      if (raw) {
        try {
          setState({ ...DEFAULTS, ...JSON.parse(raw) });
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
      saveItem(KEY, JSON.stringify(next));
      return next;
    });
  }, []);

  const reset = useCallback(() => {
    setState(DEFAULTS);
    saveItem(KEY, JSON.stringify(DEFAULTS));
  }, []);

  const value = useMemo(() => ({ ...state, update, reset }), [state, update, reset]);
  if (!ready) return null;
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): Ctx {
  const ctx = useContext(SessionContext);
  if (!ctx) throw new Error('useSession must be used inside SessionProvider');
  return ctx;
}
