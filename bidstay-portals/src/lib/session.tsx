import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

interface SessionState { userId: number | null; baseUrl: string; token?: string }
interface Ctx extends SessionState {
  signIn: (userId: number, baseUrl: string) => void;
  signInVerified: (userId: number, token: string) => void;
  signOut: () => void;
  cacheKey: string;
}

const KEY = 'bidstay.portal.session.v1';
const DEFAULT_URL = import.meta.env.DEV ? '' : import.meta.env.VITE_API_URL ?? '';

function load(): SessionState {
  if (!import.meta.env.DEV) return { userId: null, baseUrl: DEFAULT_URL };
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) {
      const saved = JSON.parse(raw);
      const baseUrl = saved.baseUrl === 'http://localhost:8080' || saved.baseUrl === import.meta.env.VITE_API_URL
        ? DEFAULT_URL : saved.baseUrl ?? DEFAULT_URL;
      return { userId: saved.userId ?? null, baseUrl };
    }
  } catch { /* ignore a corrupted value */ }
  return { userId: null, baseUrl: DEFAULT_URL };
}

const SessionContext = createContext<Ctx | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SessionState>(load);
  const save = (s: SessionState) => { setState(s); try { localStorage.setItem(KEY, JSON.stringify(s)); } catch { /* optional */ } };
  const signIn = useCallback((userId: number, baseUrl: string) => save({ userId, baseUrl: baseUrl.trim() || DEFAULT_URL }), []);
  const signInVerified = useCallback((userId: number, token: string) => {
    // Keep the bearer token in memory. Clear any stored demo identity first.
    try { localStorage.removeItem(KEY); } catch { /* optional */ }
    setState({ userId, baseUrl: state.baseUrl, token });
  }, [state.baseUrl]);
  const signOut = useCallback(() => save({ userId: null, baseUrl: state.baseUrl }), [state.baseUrl]);
  const value = useMemo(() => ({
    ...state, signIn, signInVerified, signOut,
    cacheKey: JSON.stringify([state.baseUrl, state.userId, !!state.token]),
  }), [state, signIn, signInVerified, signOut]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): Ctx {
  const ctx = useContext(SessionContext);
  if (!ctx) throw new Error('useSession must be used inside SessionProvider');
  return ctx;
}
