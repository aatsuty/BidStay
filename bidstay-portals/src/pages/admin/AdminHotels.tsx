import { useState } from 'react';
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../lib/api';
import { fmtDateTime } from '../../lib/format';
import type { AdminHotel, DocumentView, HotelStatus } from '../../lib/types';
import { DOC_LABEL, ErrorBox, HotelStatusBadge, Loading, PageHead } from '../../components/ui';

const FILTERS: [HotelStatus | 'ALL', string][] = [['PENDING_VERIFICATION', 'Awaiting approval'], ['ACTIVE', 'Live'], ['REJECTED', 'Not approved'], ['SUSPENDED', 'Suspended'], ['ALL', 'All']];

export default function AdminHotels() {
  const api = useApi();
  const [filter, setFilter] = useState<HotelStatus | 'ALL'>('PENDING_VERIFICATION');
  const q = useInfiniteQuery({
    queryKey: ['adminHotels', filter], initialPageParam: 0,
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams();
      if (filter !== 'ALL') params.set('status', filter);
      if (pageParam) params.set('before', String(pageParam));
      return api.get<{ hotels: AdminHotel[]; nextCursor?: number }>('/api/v1/admin/hotels/page?' + params);
    },
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const hotels = q.data?.pages.flatMap((page) => page.hotels);
  return (
    <>
      <PageHead title="Hotels" sub="Approve a hotel only after all four documents are verified." />
      <div className="row wrap" style={{ marginBottom: 14 }}>
        {FILTERS.map(([k, l]) => <button key={k} className={'btn sm ' + (filter === k ? '' : 'ghost')} onClick={() => setFilter(k)}>{l}</button>)}
      </div>
      <ErrorBox error={q.error} />
      {q.isLoading && <Loading />}
      {hotels && !hotels.length && <div className="card muted">No hotels in this list.</div>}
      {hotels?.map((h) => <HotelCard key={h.id} h={h} />)}
      {q.hasNextPage && <button className="btn ghost" style={{ marginTop: 14 }} disabled={q.isFetchingNextPage}
        onClick={() => { void q.fetchNextPage(); }}>Load more hotels</button>}
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
  const verify = (doc: DocumentView, on: boolean) =>
    act.mutate(`documents/${doc.type}/${on ? 'verify' : 'unverify'}${doc.versionId ? '?versionId=' + doc.versionId : ''}`);
  const download = useMutation({
    mutationFn: async (doc: DocumentView) => {
      const bytes = await api.download(`/api/v1/admin/hotels/${h.id}/documents/${doc.type}/files/${doc.versionId}`);
      const url = URL.createObjectURL(bytes);
      const link = document.createElement('a');
      link.href = url;
      link.download = doc.reference ?? 'document';
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    },
  });
  const publish = useMutation({
    mutationFn: (versionId: number) => api.post(`/api/v1/admin/hotels/${h.id}/documents/PHOTOS/publish?versionId=${versionId}`),
  });
  const verified = h.documents.filter((d) => d.verified).length;

  return (
    <div className="card">
      <div className="row spread wrap">
        <div><h2 style={{ marginBottom: 2 }}>{h.name}</h2><div className="muted small">{h.area}, {h.city} · {h.roomTypes} room type{h.roomTypes > 1 ? 's' : ''} · GST {h.gstNumber ?? '—'} · {h.contactPhone ? `+91 ${h.contactPhone}` : 'no phone'} · registered {fmtDateTime(h.createdAt)}</div></div>
        <HotelStatusBadge status={h.status} />
      </div>
      {h.rejectionReason && <div className="error" style={{ marginTop: 10 }}>Rejected: {h.rejectionReason}</div>}
      <ErrorBox error={act.error ?? download.error ?? publish.error} />
      <div style={{ marginTop: 12 }}>
        <div className="muted small bold" style={{ marginBottom: 4 }}>Documents · {verified} of {h.documents.length} verified</div>
        {h.documents.map((d) => (
          <div key={d.type} className="row wrap" style={{ padding: '8px 0', borderTop: '1px solid var(--border)' }}>
            <div style={{ width: 170 }}><b>{DOC_LABEL[d.type]}</b></div>
            <span className={'badge ' + (d.verified ? 'ok' : d.submitted ? 'warn' : 'bad')} style={{ width: 118, textAlign: 'center' }}>{d.verified ? '✓ Verified' : d.submitted ? 'To verify' : 'Missing'}</span>
            <div className="grow muted small" style={{ wordBreak: 'break-all' }}>{d.reference ?? 'Not submitted yet'}</div>
            {d.versionId && <button className="btn ghost sm" disabled={download.isPending} onClick={() => download.mutate(d)}>Download file</button>}
            {d.type === 'PHOTOS' && d.versionId && d.verified && /\.(png|jpe?g)$/i.test(d.reference ?? '') &&
              <button className="btn soft sm" disabled={publish.isPending || publish.isSuccess} onClick={() => {
                if (d.versionId && window.confirm('Publish this approved image in the guest hotel gallery?')) publish.mutate(d.versionId);
              }}>{publish.isSuccess ? 'Published to gallery' : 'Publish to guest gallery'}</button>}
            {d.submitted && (d.verified
              ? <button className="btn ghost sm" disabled={act.isPending} onClick={() => verify(d, false)}>Undo</button>
              : <button className="btn soft sm" disabled={act.isPending} onClick={() => verify(d, true)}>Mark verified</button>)}
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
