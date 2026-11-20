import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

/** AUTH PLACEHOLDER: the backend identifies callers by the X-User-Id header until OTP + JWT login exists. */
interface SessionState { userId: number | null; baseUrl: string }
interface Ctx extends SessionState { signIn: (userId: number, baseUrl: string) => void; signOut: () => void }

const KEY = 'bidstay.portal.session.v1';
const DEFAULT_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';

function load(): SessionState {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) return { userId: null, baseUrl: DEFAULT_URL, ...JSON.parse(raw) };
  } catch { /* ignore a corrupted value */ }
  return { userId: null, baseUrl: DEFAULT_URL };
}

const SessionContext = createContext<Ctx | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SessionState>(load);
  const save = (s: SessionState) => { setState(s); try { localStorage.setItem(KEY, JSON.stringify(s)); } catch { /* optional */ } };
  const signIn = useCallback((userId: number, baseUrl: string) => save({ userId, baseUrl: baseUrl.trim() || DEFAULT_URL }), []);
  const signOut = useCallback(() => save({ userId: null, baseUrl: state.baseUrl }), [state.baseUrl]);
  const value = useMemo(() => ({ ...state, signIn, signOut }), [state, signIn, signOut]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): Ctx {
  const ctx = useContext(SessionContext);
  if (!ctx) throw new Error('useSession must be used inside SessionProvider');
  return ctx;
}
