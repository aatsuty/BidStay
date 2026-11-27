import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ErrorBox } from '../../components/ui';
import { useApi } from '../../lib/api';

type SignedIn = { token: string; guest: { id: number } };
type IdentityAction = { kind: 'link'; onLinked: () => void }
  | { kind: 'email' | 'mobile'; onSignedIn: (session: SignedIn) => void };

export default function GuestIdentityForm({ action }: { action: IdentityAction }) {
  const api = useApi();
  const active = useRef(false);
  useEffect(() => {
    active.current = true;
    return () => { active.current = false; };
  }, []);
  const [address, setAddress] = useState('');
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [challenge, setChallenge] = useState<{ challengeId?: string }>();
  const sent = !!challenge;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const mobile = action.kind === 'mobile';
  const linking = action.kind === 'link';
  const base = '/api/v1/auth/guest' + (mobile ? '' : linking ? '/email/link' : '/email');

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    const identity = mobile ? { phone: address } : { email: address.trim() };
    try {
      if (!sent) {
        const response = await api.post<{ challengeId?: string } | undefined>(base + '/code', identity);
        setChallenge(response ?? {});
      } else if (action.kind === 'link') {
        await api.post(base + '/verify', { ...identity, challengeId: challenge?.challengeId, code });
        action.onLinked();
      } else {
        const verified = await api.post<SignedIn>(base + '/verify', { ...identity, challengeId: challenge?.challengeId, code, name: name.trim() });
        if (active.current) action.onSignedIn(verified);
      }
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }

  return <>
    {sent && <p role="status">Code sent to {mobile ? '+91 ' + address : address.trim()}.</p>}
    <form className="guest-identity-form" onSubmit={(event) => { void submit(event); }}>
      {sent ? <label>Verification code<input required inputMode="numeric" autoComplete="one-time-code"
        pattern="[0-9]{6}" maxLength={6} value={code} onChange={(event) => setCode(event.target.value)} /></label>
      : <>
        {mobile
          ? <label>Mobile number (+91)<input autoComplete="tel-national" required inputMode="numeric"
              pattern="[6-9][0-9]{9}" maxLength={10} value={address} onChange={(event) => setAddress(event.target.value)} /></label>
          : <label>Email address<input type="email" autoComplete="email" required maxLength={254}
              value={address} onChange={(event) => setAddress(event.target.value)} /></label>}
        {!linking && <label>Name <small>(optional)</small><input autoComplete="name" maxLength={100}
          value={name} onChange={(event) => setName(event.target.value)} /></label>}
      </>}
      <ErrorBox error={error} />
      <button className="guest-cta" disabled={busy}>
        {busy ? 'Please wait…' : !sent ? 'Send code' : linking ? 'Verify and link email' : 'Verify and sign in'}
      </button>
    </form>
    {sent && <button type="button" className="guest-quiet" disabled={busy} onClick={() => {
      setChallenge(undefined);
      setCode('');
      setError(undefined);
    }}>{mobile ? 'Change number or send again' : 'Change email or send again'}</button>}
  </>;
}
