import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Mail, MessageSquare } from 'lucide-react';
import { useApi } from '../../lib/api';
import { useSession } from '../../lib/session';
import { ErrorBox } from '../../components/ui';
import WhatsAppPreference from '../WhatsAppPreference';

type Contacts = { email?: string; smsEnabled: boolean; phoneVerified: boolean; smsAvailable: boolean; emailAvailable: boolean };
const endpoint = '/api/v1/guest/ticket-contacts';

export default function TicketContactPreferences() {
  const api = useApi();
  const client = useQueryClient();
  const { token, cacheKey } = useSession();
  const key = ['ticket-contacts', cacheKey];
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [sent, setSent] = useState(false);
  const contact = useQuery({ queryKey: key, enabled: !!token, queryFn: () => api.get<Contacts>(endpoint), retry: false });
  const sms = useMutation({
    mutationFn: (enabled: boolean) => api.put<Contacts>(endpoint + '/sms', { enabled }),
    onSuccess: (data) => client.setQueryData(key, data),
  });
  const request = useMutation({
    mutationFn: () => api.post('/api/v1/auth/guest/ticket-email/code', { email: email.trim() }),
    onSuccess: () => setSent(true),
  });
  const verify = useMutation({
    mutationFn: () => api.post<Contacts>('/api/v1/auth/guest/ticket-email/verify', { code }),
    onSuccess: (data) => {
      client.setQueryData(key, data);
      setSent(false);
      setCode('');
      setEmail('');
    },
  });
  const remove = useMutation({
    mutationFn: () => api.delete(endpoint + '/email'),
    onSuccess: () => { void client.invalidateQueries({ queryKey: key }); },
  });

  if (!token) return null;
  const data = contact.data;
  const smsVisible = data?.smsAvailable && data.phoneVerified;
  return <>
    {(data?.emailAvailable || data?.email || smsVisible) && <section className="guest-ticket-contacts" aria-label="Booking ticket delivery">
      <h3>Booking ticket delivery</h3>
      {smsVisible && <label className="guest-ticket-toggle"><MessageSquare size={16} /> Send confirmed tickets by SMS
        <input type="checkbox" checked={data.smsEnabled} disabled={sms.isPending} onChange={(event) => sms.mutate(event.target.checked)} />
      </label>}
      {data?.email && <div className="guest-ticket-address"><Mail size={16} /> <span>Email tickets to {data.email}</span>
        <button className="guest-quiet" type="button" disabled={remove.isPending} onClick={() => remove.mutate()}>Remove email</button>
      </div>}
      {data?.emailAvailable && <form className="guest-ticket-email" onSubmit={(event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (sent) verify.mutate(); else request.mutate();
      }}>
        {sent ? <label className="field">Code sent to {email}
          <input className="input" inputMode="numeric" autoComplete="one-time-code" required pattern="[0-9]{6}" maxLength={6}
            value={code} onChange={(event) => setCode(event.target.value)} />
        </label> : <label className="field">{data.email ? 'Change ticket email' : 'Add a ticket email'}
          <input className="input" type="email" autoComplete="email" required maxLength={254}
            value={email} onChange={(event) => setEmail(event.target.value)} placeholder="you@example.com" />
        </label>}
        <button className="guest-quiet" disabled={request.isPending || verify.isPending}>{sent ? 'Verify email' : 'Send verification code'}</button>
        {sent && <button className="guest-quiet" type="button" disabled={verify.isPending} onClick={() => {
          setSent(false);
          setCode('');
          verify.reset();
        }}>Change email or resend</button>}
      </form>}
      <ErrorBox error={sms.error ?? request.error ?? verify.error ?? remove.error} />
    </section>}
    <ErrorBox error={contact.error} />
    <WhatsAppPreference endpoint="/api/v1/whatsapp/guest" label="Get tickets and trip updates on WhatsApp" />
  </>;
}
