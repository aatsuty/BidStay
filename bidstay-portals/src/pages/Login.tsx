import { useEffect, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, BedDouble, ShieldCheck } from 'lucide-react';
import { make, useApi } from '../lib/api';
import { demoMode } from '../lib/runtime';
import { useSession } from '../lib/session';
import type { Me } from '../lib/types';
import { ErrorBox } from '../components/ui';

const ACCOUNTS = [
  { id: 3, title: 'Hotel owner', sub: 'Owns Hotel Sunrise Paharganj and Hotel Metro Inn (live hotels)', tag: 'Hotelier portal' },
  { id: 5, title: 'New hotel owner', sub: 'Owns Delhi Grand Stay and Pahar Palace (waiting for approval)', tag: 'Hotelier portal' },
  { id: 4, title: 'BidStay admin', sub: 'Approves hotels, watches bids, bookings and commission', tag: 'Admin portal' },
];

export default function Login() {
  const session = useSession();
  const api = useApi();
  const nav = useNavigate();
  const link: unknown = useLocation().state?.bookingLink;
  const hotelHome = typeof link === 'string' && /^\/hotel\/bookings\?hotel=\d+&booking=\d+$/.test(link) ? link : '/hotel';
  const qc = useQueryClient();
  const [invite] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get('invite') || undefined);
  const [mode, setMode] = useState<'staff' | 'admin'>('staff');
  const [url, setUrl] = useState(session.baseUrl);
  const [phone, setPhone] = useState('');
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (invite) window.history.replaceState(window.history.state, '', window.location.pathname + window.location.search);
  }, [invite]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (mode === 'staff' && !sent) {
        await api.post('/api/v1/auth/staff/code', { phone });
        setSent(true);
        return;
      }
      const admin = mode === 'admin';
      const authenticated = await api.post<{ token: string; guest: { id: number } }>(
        admin ? '/api/v1/auth/admin/sign-in' : '/api/v1/auth/staff/verify',
        admin ? { phone, password, code } : { phone, code, name, invite });
      qc.clear();
      session.signInVerified(authenticated.guest.id, authenticated.token);
      nav(admin ? '/admin' : hotelHome, { replace: true });
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }

  async function enter(userId: number) {
    setBusy(true);
    setError(null);
    try {
      const me = await make(url, userId).get<Me>('/api/v1/me');
      qc.clear();
      session.signIn(userId, url);
      nav(me.role === 'ADMIN' ? '/admin' : hotelHome, { replace: true });
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }

  function changeMode(next: 'staff' | 'admin') {
    setMode(next);
    setSent(false);
    setCode('');
    setPassword('');
    setError(null);
  }

  return (
    <div className="login portal-login">
      <div className="login-box">
        <Link to="/" className="portal-back"><ArrowLeft size={16} /> Find a stay</Link>
        <div className="portal-login-panel">
          <div className="portal-login-brand"><span><BedDouble size={22} /></span> Bid<b>Stay</b></div>
          <h1>{invite ? 'Accept your hotel invitation' : 'Sign in to BidStay'}</h1>
          {!invite && <div className="portal-login-tabs">
            <button type="button" disabled={busy} aria-pressed={mode === 'staff'} onClick={() => changeMode('staff')}>Hotel partner</button>
            <button type="button" disabled={busy} aria-pressed={mode === 'admin'} onClick={() => changeMode('admin')}>Admin</button>
          </div>}
          {invite && <p className="muted small">Verify the mobile number your hotel invited.</p>}
          <form className="portal-login-form" onSubmit={(event) => { void submit(event); }}>
            <label className="field">Mobile number (+91)
              <input className="input" required autoComplete="tel-national" inputMode="numeric" disabled={sent && mode === 'staff'} pattern="[6-9][0-9]{9}" maxLength={10} value={phone} onChange={(event) => setPhone(event.target.value)} />
            </label>
            {invite && !sent && <label className="field">Name<input className="input" autoComplete="name" maxLength={100} value={name} onChange={(event) => setName(event.target.value)} /></label>}
            {mode === 'admin' && <label className="field">Password<input className="input" required type="password" autoComplete="current-password" maxLength={72} value={password} onChange={(event) => setPassword(event.target.value)} /></label>}
            {(sent || mode === 'admin') && <label className="field">{mode === 'admin' ? 'Authenticator code' : 'SMS code'}
              <input className="input" required autoComplete="one-time-code" inputMode="numeric" pattern="[0-9]{6}" maxLength={6} value={code} onChange={(event) => setCode(event.target.value)} />
            </label>}
            <ErrorBox error={error} />
            <button className="btn" disabled={busy}>{busy ? 'Please wait…' : sent || mode === 'admin' ? 'Sign in' : 'Send verification code'}</button>
          </form>
          {sent && <button className="btn ghost sm" style={{ marginTop: 10 }} disabled={busy} onClick={() => { setSent(false); setCode(''); }}>Change number or resend</button>}
          {mode === 'admin' && <p className="portal-security"><ShieldCheck size={15} /> Your administrator account requires two-step verification.</p>}
        </div>
        {demoMode && !invite && <details className="portal-demo">
          <summary>Local demo accounts</summary>
          {ACCOUNTS.map((account) => <button key={account.id} className="acct" disabled={busy} onClick={() => { void enter(account.id); }}>
            <div className="row spread"><b>{account.title}</b><span className="badge info">{account.tag}</span></div>
            <div className="muted small">{account.sub}</div>
          </button>)}
          <label className="field">Demo backend URL<input className="input" placeholder="Use the portal's backend" value={url} onChange={(event) => setUrl(event.target.value)} /></label>
        </details>}
      </div>
    </div>
  );
}
