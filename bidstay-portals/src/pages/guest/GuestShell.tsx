import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, NavLink, Outlet, useLocation, useOutletContext, useSearchParams } from 'react-router-dom';
import { BedDouble, Bell, Building2, CalendarDays, ChevronRight, CircleUserRound, LogOut, X } from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { useMe } from '../../App';
import { ErrorBox } from '../../components/ui';
import { useApi } from '../../lib/api';
import { useSession } from '../../lib/session';
import './guest.css';

type GuestContext = { signedIn: boolean; checkingSignIn: boolean; openSignIn: () => void };
export const useGuest = () => useOutletContext<GuestContext>();

export default function GuestShell() {
  const session = useSession();
  const api = useApi();
  const me = useMe();
  const { pathname } = useLocation();
  const [params, setParams] = useSearchParams();
  const [showSignIn, setShowSignIn] = useState(() => params.get('signIn') === '1');
  const [logoutError, setLogoutError] = useState<unknown>();
  const signedIn = me.data?.role === 'GUEST';
  const checkingSignIn = session.userId !== null && me.isPending;
  const alerts = useQuery({
    queryKey: ['guest-alert-count', session.cacheKey],
    queryFn: () => api.get<{ count: number }>('/api/v1/guest/alerts/unread'),
    enabled: signedIn,
    refetchInterval: 30_000,
    refetchOnWindowFocus: 'always',
  });
  const unread = alerts.data?.count ?? 0;
  useEffect(() => { window.scrollTo({ top: 0, behavior: 'instant' }); }, [pathname]);

  async function logout() {
    try {
      if (session.token) await api.post('/api/v1/auth/logout');
      session.signOut();
      setLogoutError(undefined);
    } catch (error) {
      setLogoutError(error);
    }
  }

  return (
    <div className="guest">
      <header className="guest-nav">
        <div className="guest-container guest-nav-inner">
          <Link to="/" className="guest-wordmark"><span><BedDouble size={21} /></span>Bid<strong>Stay</strong></Link>
          <nav aria-label="Guest navigation">
            <NavLink to="/" end>Find a stay</NavLink>
            <NavLink to="/trips"><CalendarDays size={16} /> My trips</NavLink>
            <NavLink to="/alerts"><Bell size={16} /> Alerts {unread > 0 && <span className="guest-alert-badge" aria-label={`${unread} unread alerts`}>{unread > 99 ? '99+' : unread}</span>}</NavLink>
          </nav>
          <div className="guest-nav-actions">
            <Link to={me.data?.role === 'ADMIN' ? '/admin' : me.data?.role === 'HOTEL_STAFF' ? '/hotel' : '/login'} className="guest-partner">
              <Building2 size={16} /> Partner portal
            </Link>
            {signedIn ? (
              <button className="guest-quiet" onClick={() => { void logout(); }}><LogOut size={16} /> Sign out</button>
            ) : (
              <button className="guest-quiet" onClick={() => setShowSignIn(true)}><CircleUserRound size={17} /> Sign in</button>
            )}
          </div>
        </div>
      </header>
      <div className="guest-container"><ErrorBox error={logoutError} /></div>
      <Outlet context={{ signedIn, checkingSignIn, openSignIn: () => setShowSignIn(true) } satisfies GuestContext} />
      <footer className="guest-footer"><div className="guest-container"><Link to="/" className="guest-wordmark">Bid<strong>Stay</strong></Link><Link to="/login">Hotel partner &amp; admin portal <ChevronRight size={14} /></Link></div></footer>
      {showSignIn && <GuestSignIn close={() => {
        setShowSignIn(false);
        if (params.has('signIn')) {
          const next = new URLSearchParams(params);
          next.delete('signIn');
          setParams(next, { replace: true });
        }
      }} />}
    </div>
  );
}

function GuestSignIn({ close }: { close: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const api = useApi();
  const session = useSession();
  const [phone, setPhone] = useState('');
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  useEffect(() => { dialog.current?.showModal(); }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      if (!sent) {
        await api.post('/api/v1/auth/guest/code', { phone });
        setSent(true);
      } else {
        const verified = await api.post<{ token: string; guest: { id: number } }>('/api/v1/auth/guest/verify', { phone, code, name: name.trim() });
        session.signInVerified(verified.guest.id, verified.token);
        close();
      }
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }

  async function demo() {
    setBusy(true);
    setError(undefined);
    try {
      const { make } = await import('../../lib/api');
      await make(session.baseUrl, 1).get('/api/v1/me');
      session.signIn(1, session.baseUrl);
      close();
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }

  return (
    <dialog ref={dialog} className="guest-dialog" aria-labelledby="guest-sign-in" onCancel={close}>
      <button type="button" className="guest-close" onClick={close} aria-label="Close"><X size={21} /></button>
      <h2 id="guest-sign-in">{sent ? 'Enter your verification code' : 'Sign in or create an account'}</h2>
      <p>{sent ? `Code sent to +91 ${phone}.` : 'Use your mobile number to make bids and manage trips.'}</p>
      <form onSubmit={(event) => { void submit(event); }}>
        {!sent ? <>
          <label>Mobile number (+91)<input autoComplete="tel-national" required inputMode="numeric" pattern="[6-9][0-9]{9}" maxLength={10} value={phone} onChange={(event) => setPhone(event.target.value)} /></label>
          <label>Name <small>(optional)</small><input autoComplete="name" maxLength={100} value={name} onChange={(event) => setName(event.target.value)} /></label>
        </> : <label>Verification code<input required inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} value={code} onChange={(event) => setCode(event.target.value)} /></label>}
        <ErrorBox error={error} />
        <button className="guest-cta" disabled={busy}>{busy ? 'Please wait…' : sent ? 'Verify and sign in' : 'Send code'}</button>
      </form>
      {sent && <button className="guest-quiet" onClick={() => { setSent(false); setCode(''); setError(undefined); }}>Change number or send again</button>}
      {import.meta.env.DEV && <button className="guest-quiet" disabled={busy} onClick={() => { void demo(); }}>Continue with demo guest</button>}
    </dialog>
  );
}
