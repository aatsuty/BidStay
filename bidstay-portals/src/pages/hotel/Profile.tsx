import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../lib/api';
import type { DocType, DocumentView } from '../../lib/types';
import { DOC_LABEL, ErrorBox, HotelStatusBadge, Loading, PageHead } from '../../components/ui';
import { useHotel } from './HotelContext';

export default function Profile() {
  const { hotel } = useHotel();
  const api = useApi();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['docs', hotel.id], queryFn: () => api.get<DocumentView[]>(`/api/v1/partner/hotels/${hotel.id}/documents`) });
  const [draft, setDraft] = useState<Partial<Record<DocType, File>>>({});

  const submit = useMutation({
    mutationFn: ({ type, file }: { type: DocType; file: File }) => {
      const form = new FormData();
      form.append('file', file);
      return api.post<DocumentView[]>(`/api/v1/partner/hotels/${hotel.id}/documents/${type}/file`, form);
    },
    onSuccess: (docs, v) => {
      qc.setQueryData(['docs', hotel.id], docs);
      setDraft((d) => ({ ...d, [v.type]: undefined }));
      qc.invalidateQueries({ queryKey: ['partnerHotels'] });
      qc.invalidateQueries({ queryKey: ['chat', hotel.id] });
    },
  });

  return (
    <>
      <PageHead title={hotel.name} sub={hotel.area}><HotelStatusBadge status={hotel.status} /></PageHead>
      <div className="card">
        <h2>Verification documents</h2>
        <p className="muted small">Upload PDF, JPEG or PNG files up to 5 MB. Replacing an approved document sends the hotel back for review.</p>
        <ErrorBox error={submit.error} />
        {q.isLoading && <Loading />}
        {q.data?.map((d) => (
          <div key={d.type} className="row wrap" style={{ padding: '10px 0', borderTop: '1px solid var(--border)' }}>
            <div style={{ width: 190 }}><b>{DOC_LABEL[d.type]}</b></div>
            <span className={'badge ' + (d.verified ? 'ok' : d.submitted ? 'warn' : '')} style={{ width: 120, textAlign: 'center' }}>{d.verified ? '✓ Verified' : d.submitted ? 'Under review' : 'Not submitted'}</span>
            <label className="field grow" style={{ minWidth: 190 }}>{d.reference ?? 'Choose document'}<input key={`${d.type}-${d.versionId}`} className="input" type="file"
              accept="application/pdf,image/jpeg,image/png" onChange={(event) => setDraft({ ...draft, [d.type]: event.target.files?.[0] })} /></label>
            <button className="btn soft sm" disabled={!draft[d.type] || submit.isPending} onClick={() => {
              const file = draft[d.type];
              if (file) submit.mutate({ type: d.type, file });
            }}>{d.submitted ? 'Replace file' : 'Upload file'}</button>
          </div>
        ))}
      </div>
      <StaffInvites key={hotel.id} hotelId={hotel.id} />
    </>
  );
}


function StaffInvites({ hotelId }: { hotelId: number }) {
  const api = useApi();
  const [phone, setPhone] = useState('');
  const [copied, setCopied] = useState(false);
  const invite = useMutation({
    mutationFn: () => api.post<{ token: string }>(`/api/v1/partner/hotels/${hotelId}/invites`, { phone }),
    onSuccess: () => setCopied(false),
  });
  const link = invite.data ? window.location.origin + '/login#invite=' + invite.data.token : '';
  return <section className="card">
    <h2>Invite hotel staff</h2>
    <p className="muted small">The invitation is valid for 48 hours and only works for this hotel and phone number.</p>
    <form className="row wrap" onSubmit={(event) => { event.preventDefault(); invite.mutate(); }}>
      <label className="field">Mobile number (+91)<input className="input" required pattern="[6-9][0-9]{9}" inputMode="numeric" maxLength={10} value={phone} onChange={(event) => setPhone(event.target.value)} /></label>
      <button className="btn" style={{ alignSelf: 'flex-end' }} disabled={invite.isPending}>Create invitation</button>
    </form>
    <ErrorBox error={invite.error} />
    {link && <div className="row wrap" style={{ marginTop: 10 }}>
      <label className="field grow">Invitation link<input className="input" value={link} readOnly onFocus={(event) => event.currentTarget.select()} /></label>
      <button className="btn ghost" style={{ alignSelf: 'flex-end' }} onClick={() => { void navigator.clipboard?.writeText(link).then(() => setCopied(true)).catch(() => setCopied(false)); }}>{copied ? 'Copied' : 'Copy link'}</button>
    </div>}
  </section>;
}
