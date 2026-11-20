import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useSession } from '../lib/session';
import type { Me } from '../lib/types';
import { ErrorBox } from '../components/ui';

const ACCOUNTS = [
  { id: 3, title: 'Hotel owner', sub: 'Owns Hotel Sunrise Paharganj and Hotel Metro Inn (live hotels)', tag: 'Hotelier portal' },
  { id: 5, title: 'New hotel owner', sub: 'Owns Delhi Grand Stay and Pahar Palace (waiting for approval)', tag: 'Hotelier portal' },
  { id: 4, title: 'BidStay admin', sub: 'Approves hotels, watches bids, bookings and commission', tag: 'Admin portal' },
];

/** DEV LOGIN. Replaced by phone number + OTP once the backend issues real tokens. */
export default function Login() {
  const session = useSession();
  const nav = useNavigate();
  const qc = useQueryClient();
  const [url, setUrl] = useState(session.baseUrl);
  const [custom, setCustom] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function enter(userId: number) {
    setBusy(true); setError(null);
    try {
      const res = await fetch(url.replace(/\/$/, '') + '/api/v1/me', { headers: { 'X-User-Id': String(userId) } });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.detail ?? `Login failed (${res.status})`);
      const me = data as Me;
      if (me.role === 'GUEST') throw new Error('Guests use the BidStay mobile app. This site is for hotels and admins.');
      qc.clear();
      session.signIn(userId, url);
      nav(me.role === 'ADMIN' ? '/admin' : '/hotel', { replace: true });
    } catch (e) {
      setError(e instanceof TypeError ? new Error(`Can't reach the server at ${url}. Is the backend running?`) : e);
    } finally { setBusy(false); }
  }

  return (
    <div className="login">
      <div className="login-box">
        <div className="logo" style={{ color: '#3730a3', padding: 0, fontSize: 34 }}>Bid<b>Stay</b></div>
        <p className="muted" style={{ marginTop: 4 }}>Partner &amp; admin portal · Apna Rate, Apna Stay</p>
        <div className="notice">Developer login. Real phone + OTP sign-in is the next step, so anyone can pick any account for now.</div>
        <ErrorBox error={error} />
        {ACCOUNTS.map((a) => (
          <button key={a.id} className="acct" disabled={busy} onClick={() => enter(a.id)}>
            <div className="row spread"><b>{a.title}</b><span className="badge info">{a.tag}</span></div>
            <div className="muted small">{a.sub}</div>
          </button>
        ))}
        <div className="card" style={{ marginTop: 16 }}>
          <div className="row wrap">
            <label className="field grow">Backend URL<input className="input" value={url} onChange={(e) => setUrl(e.target.value)} /></label>
            <label className="field" style={{ width: 120 }}>User ID<input className="input" value={custom} onChange={(e) => setCustom(e.target.value.replace(/\D/g, ''))} placeholder="e.g. 3" /></label>
            <button className="btn soft" style={{ alignSelf: 'flex-end' }} disabled={!custom || busy} onClick={() => enter(Number(custom))}>Sign in</button>
          </div>
        </div>
      </div>
    </div>
  );
}
