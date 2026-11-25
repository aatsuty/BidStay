import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, useApi } from '../../lib/api';
import { fmtDateTime } from '../../lib/format';
import { deviceRequest } from '../../lib/portal-device';
import { useSession } from '../../lib/session';
import { ErrorBox, Loading } from '../../components/ui';

interface Team {
  members: { id: number; name?: string; phone: string; manager: boolean; removable: boolean }[];
  invitations: { id: number; phone: string; manager: boolean; expiresAt: string; revocable: boolean }[];
  canInviteManagers: boolean;
}

export default function StaffAccess({ hotelId }: { hotelId: number }) {
  const api = useApi();
  const client = useQueryClient();
  const { cacheKey } = useSession();
  const [phone, setPhone] = useState('');
  const [manager, setManager] = useState(false);
  const [copied, setCopied] = useState(false);
  const endpoint = `/api/v1/partner/hotels/${hotelId}`;
  const key = ['hotel-staff-access', cacheKey, hotelId];
  const team = useQuery({
    queryKey: key, queryFn: () => api.get<Team>(endpoint + '/staff'),
    retry: false, refetchOnWindowFocus: 'always',
  });
  const refresh = () => { void client.invalidateQueries({ queryKey: key }); };
  const invite = useMutation({
    mutationFn: () => api.post<{ token: string }>(endpoint + '/invites', { phone, manager }),
    onSuccess: () => { setCopied(false); refresh(); },
  });
  const revoke = useMutation({
    mutationFn: ({ id, pending }: { id: number; pending: boolean }) =>
      api.delete(endpoint + (pending ? '/invites/' : '/staff/') + id),
    onSuccess: () => { invite.reset(); refresh(); },
  });
  const link = invite.data ? window.location.origin + '/login#invite=' + invite.data.token : '';

  if (team.error instanceof ApiError && team.error.status === 403) return null;
  return <section className="card staff-access">
    <h2>Staff access</h2>
    <p className="muted small">Invitations expire after 48 hours. Removing a staff member ends their access to this hotel.</p>
    <ErrorBox error={team.error ?? invite.error ?? revoke.error} />
    {team.isLoading && <Loading />}
    {team.data && <>
      <form className="row wrap" onSubmit={(event) => { event.preventDefault(); invite.mutate(); }}>
        <label className="field">Mobile number (+91)<input className="input" required pattern="[6-9][0-9]{9}" inputMode="numeric"
          maxLength={10} value={phone} onChange={(event) => setPhone(event.target.value)} /></label>
        {team.data.canInviteManagers && <label className="field">Property role<select className="input" value={manager ? 'manager' : 'staff'}
          onChange={(event) => setManager(event.target.value === 'manager')}><option value="staff">Staff</option><option value="manager">Manager</option></select></label>}
        <button className="btn" style={{ alignSelf: 'flex-end' }} disabled={invite.isPending}>Create invitation</button>
      </form>
      {link && <div className="row wrap" style={{ marginTop: 10 }}>
        <label className="field grow">Invitation link<input className="input" value={link} readOnly onFocus={(event) => event.currentTarget.select()} /></label>
        <button className="btn ghost" style={{ alignSelf: 'flex-end' }} onClick={() => {
          const copy = deviceRequest({ action: 'copy', text: link }) ?? navigator.clipboard?.writeText(link);
          void copy?.then(() => setCopied(true)).catch(() => setCopied(false));
        }}>{copied ? 'Copied' : 'Copy link'}</button>
      </div>}
      <h3>Current team</h3>
      {team.data.members.length === 0 && <p className="muted small">No staff members assigned.</p>}
      <div className="staff-access-list">{team.data.members.map((member) =>
        <div className="row wrap" key={member.id}>
          <div className="grow"><strong>{member.name ?? 'Hotel partner'}</strong><div className="muted small">+91 {member.phone}</div></div>
          <span className="badge info">{member.manager ? 'Manager' : 'Staff'}</span>
          {member.removable && <button className="btn ghost sm" disabled={revoke.isPending} onClick={() => {
            if (window.confirm('Remove access to this hotel for this staff member?')) revoke.mutate({ id: member.id, pending: false });
          }}>Remove access</button>}
        </div>)}</div>
      {team.data.invitations.length > 0 && <>
        <h3>Pending invitations</h3>
        <div className="staff-access-list">{team.data.invitations.map((pending) =>
          <div className="row wrap" key={pending.id}>
            <div className="grow"><strong>+91 {pending.phone}</strong><div className="muted small">Expires {fmtDateTime(pending.expiresAt)}</div></div>
            <span className="badge">{pending.manager ? 'Manager' : 'Staff'}</span>
            {pending.revocable && <button className="btn ghost sm" disabled={revoke.isPending} onClick={() => {
              if (window.confirm('Revoke this pending invitation?')) revoke.mutate({ id: pending.id, pending: true });
            }}>Revoke invitation</button>}
          </div>)}</div>
      </>}
    </>}
  </section>;
}
