import { useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useOutletContext, useSearchParams } from 'react-router-dom';
import { BedDouble, Bell, Building2, CalendarDays, ChevronRight, CircleUserRound, LogOut, Search, X } from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { useMe } from '../../App';
import { ErrorBox } from '../../components/ui';
import { make, useApi } from '../../lib/api';
import { demoMode } from '../../lib/runtime';
import { useSession } from '../../lib/session';
import GuestIdentityForm from './GuestIdentityForm';
import './guest.css';

type GuestContext = { signedIn: boolean; checkingSignIn: boolean; emailEnabled: boolean; openSignIn: () => void };
export const useGuest = () => useOutletContext<GuestContext>();

export default function GuestShell() {
  const session = useSession();
  const api = useApi();
  const me = useMe();
  const methods = useQuery({
    queryKey: ['guest-sign-in-methods', session.baseUrl],
    queryFn: () => api.get<{ email: boolean }>('/api/v1/auth/guest/methods'),
    staleTime: 60_000,
    retry: false,
  });
  const emailEnabled = methods.data?.email === true;
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

  const accountButton = (className?: string) => signedIn
    ? <button className={className} onClick={() => { void logout(); }}><LogOut size={18} /> Sign out</button>
    : <button className={className} onClick={() => setShowSignIn(true)}><CircleUserRound size={18} /> Sign in</button>;
  const guestLinks = <>
    <NavLink to="/" end className={({ isActive }) => isActive || pathname === '/hourly' ? 'active' : ''}>
      <Search size={17} /> <span>Find a stay</span>
    </NavLink>
    <NavLink to="/trips"><CalendarDays size={17} /> <span>My trips</span></NavLink>
    <NavLink to="/alerts"><Bell size={17} /> <span>Alerts</span>
      {unread > 0 && <span className="guest-alert-badge" aria-label={`${unread} unread alerts`}>{unread > 99 ? '99+' : unread}</span>}
    </NavLink>
  </>;

  return (
    <div className="guest">
      <header className="guest-nav">
        <div className="guest-container guest-nav-inner">
          <Link to="/" className="guest-wordmark"><span><BedDouble size={21} /></span>Bid<strong>Stay</strong></Link>
          <nav className="guest-desktop-nav" aria-label="Guest navigation">{guestLinks}</nav>
          <div className="guest-nav-actions">
            <Link to={me.data?.role === 'ADMIN' ? '/admin' : me.data?.role === 'HOTEL_STAFF' ? '/hotel' : '/login'} className="guest-partner">
              <Building2 size={16} /> Partner portal
            </Link>
            {accountButton('guest-quiet')}
          </div>
        </div>
      </header>
      <div className="guest-container"><ErrorBox error={logoutError} /></div>
      <Outlet context={{ signedIn, checkingSignIn, emailEnabled, openSignIn: () => setShowSignIn(true) } satisfies GuestContext} />
      <footer className="guest-footer"><div className="guest-container"><Link to="/" className="guest-wordmark">Bid<strong>Stay</strong></Link><Link to="/login">Hotel partner &amp; admin portal <ChevronRight size={14} /></Link></div></footer>
      <nav className="guest-mobile-nav" aria-label="Guest navigation">{guestLinks}{accountButton()}</nav>
      {showSignIn && <GuestSignIn emailEnabled={emailEnabled} close={() => {
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

function GuestSignIn({ close, emailEnabled }: { close: () => void; emailEnabled: boolean }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const session = useSession();
  const [method, setMethod] = useState<'email' | 'mobile'>(emailEnabled ? 'email' : 'mobile');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  useEffect(() => { dialog.current?.showModal(); }, []);

  async function demo() {
    setBusy(true);
    setError(undefined);
    try {
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
      <h2 id="guest-sign-in">Sign in or create an account</h2>
      <p>{emailEnabled ? 'Get a one-time code by email or SMS.' : 'Use your mobile number to make bids and manage trips.'}</p>
      {emailEnabled && <div className="guest-sign-in-methods" role="group" aria-label="Sign-in method">
        <button type="button" className="guest-quiet" aria-pressed={method === 'email'} onClick={() => setMethod('email')}>Email</button>
        <button type="button" className="guest-quiet" aria-pressed={method === 'mobile'} onClick={() => setMethod('mobile')}>Mobile</button>
      </div>}
      <GuestIdentityForm key={method} action={{ kind: method, onSignedIn: ({ guest, token }) => {
        session.signInVerified(guest.id, token);
        close();
      } }} />
      <ErrorBox error={error} />
      {demoMode && <button className="guest-quiet" disabled={busy} onClick={() => { void demo(); }}>Continue with demo guest</button>}
    </dialog>
  );
}
