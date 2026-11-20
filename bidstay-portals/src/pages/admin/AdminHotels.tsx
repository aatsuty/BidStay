import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../lib/api';
import { fmtDateTime } from '../../lib/format';
import type { AdminHotel, DocType, HotelStatus } from '../../lib/types';
import { DOC_LABEL, ErrorBox, HotelStatusBadge, Loading, PageHead } from '../../components/ui';

const FILTERS: [HotelStatus | 'ALL', string][] = [['PENDING_VERIFICATION', 'Awaiting approval'], ['ACTIVE', 'Live'], ['REJECTED', 'Not approved'], ['SUSPENDED', 'Suspended'], ['ALL', 'All']];

export default function AdminHotels() {
  const api = useApi();
  const [filter, setFilter] = useState<HotelStatus | 'ALL'>('PENDING_VERIFICATION');
  const q = useQuery({ queryKey: ['adminHotels', filter], queryFn: () => api.get<AdminHotel[]>('/api/v1/admin/hotels' + (filter === 'ALL' ? '' : `?status=${filter}`)) });
  return (
    <>
      <PageHead title="Hotels" sub="Approve a hotel only after all four documents are verified." />
      <div className="row wrap" style={{ marginBottom: 14 }}>
        {FILTERS.map(([k, l]) => <button key={k} className={'btn sm ' + (filter === k ? '' : 'ghost')} onClick={() => setFilter(k)}>{l}</button>)}
      </div>
      <ErrorBox error={q.error} />
      {q.isLoading && <Loading />}
      {q.data && !q.data.length && <div className="card muted">No hotels in this list.</div>}
      {q.data?.map((h) => <HotelCard key={h.id} h={h} />)}
    </>
  );
}

function HotelCard({ h }: { h: AdminHotel }) {
  const api = useApi();
  const qc = useQueryClient();
  const [reason, setReason] = useState('');
  const [rejecting, setRejecting] = useState(false);
  const done = () => { qc.invalidateQueries({ queryKey: ['adminHotels'] }); qc.invalidateQueries({ queryKey: ['adminStats'] }); };
  const act = useMutation({ mutationFn: (path: string) => api.post<AdminHotel>(`/api/v1/admin/hotels/${h.id}/${path}`, path === 'reject' ? { reason } : {}), onSuccess: () => { setRejecting(false); setReason(''); done(); } });
  const verify = (t: DocType, on: boolean) => act.mutate(`documents/${t}/${on ? 'verify' : 'unverify'}`);
  const verified = h.documents.filter((d) => d.verified).length;

  return (
    <div className="card">
      <div className="row spread wrap">
        <div><h2 style={{ marginBottom: 2 }}>{h.name}</h2><div className="muted small">{h.area}, {h.city} · {h.roomTypes} room type{h.roomTypes > 1 ? 's' : ''} · GST {h.gstNumber ?? '—'} · {h.contactPhone ? `+91 ${h.contactPhone}` : 'no phone'} · registered {fmtDateTime(h.createdAt)}</div></div>
        <HotelStatusBadge status={h.status} />
      </div>
      {h.rejectionReason && <div className="error" style={{ marginTop: 10 }}>Rejected: {h.rejectionReason}</div>}
      <ErrorBox error={act.error} />
      <div style={{ marginTop: 12 }}>
        <div className="muted small bold" style={{ marginBottom: 4 }}>Documents · {verified} of {h.documents.length} verified</div>
        {h.documents.map((d) => (
          <div key={d.type} className="row wrap" style={{ padding: '8px 0', borderTop: '1px solid var(--border)' }}>
            <div style={{ width: 170 }}><b>{DOC_LABEL[d.type]}</b></div>
            <span className={'badge ' + (d.verified ? 'ok' : d.submitted ? 'warn' : 'bad')} style={{ width: 118, textAlign: 'center' }}>{d.verified ? '✓ Verified' : d.submitted ? 'To verify' : 'Missing'}</span>
            <div className="grow muted small" style={{ wordBreak: 'break-all' }}>{d.reference ?? 'Not submitted yet'}</div>
            {d.submitted && (d.verified
              ? <button className="btn ghost sm" disabled={act.isPending} onClick={() => verify(d.type, false)}>Undo</button>
              : <button className="btn soft sm" disabled={act.isPending} onClick={() => verify(d.type, true)}>Mark verified</button>)}
          </div>
        ))}
      </div>
      <div className="row wrap" style={{ marginTop: 14 }}>
        {(h.status === 'PENDING_VERIFICATION' || h.status === 'REJECTED') && <button className="btn ok" disabled={!h.allDocsVerified || act.isPending} title={h.allDocsVerified ? '' : 'Verify all four documents first'} onClick={() => act.mutate('approve')}>Approve &amp; go live</button>}
        {h.status === 'PENDING_VERIFICATION' && !rejecting && <button className="btn danger" onClick={() => setRejecting(true)}>Reject…</button>}
        {h.status === 'ACTIVE' && <button className="btn danger" disabled={act.isPending} onClick={() => act.mutate('suspend')}>Suspend</button>}
        {h.status === 'SUSPENDED' && <button className="btn" disabled={act.isPending} onClick={() => act.mutate('reinstate')}>Reinstate</button>}
        {!h.allDocsVerified && (h.status === 'PENDING_VERIFICATION' || h.status === 'REJECTED') && <span className="muted small">Verify all four documents to enable approval.</span>}
      </div>
      {rejecting && (
        <div className="row wrap" style={{ marginTop: 10 }}>
          <input className="input grow" placeholder="Reason shown to the owner, e.g. GST number does not match" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={300} />
          <button className="btn danger" disabled={!reason.trim() || act.isPending} onClick={() => act.mutate('reject')}>Confirm reject</button>
          <button className="btn ghost" onClick={() => setRejecting(false)}>Cancel</button>
        </div>)}
    </div>
  );
}
