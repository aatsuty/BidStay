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
  const [draft, setDraft] = useState<Partial<Record<DocType, string>>>({});

  const submit = useMutation({
    mutationFn: (v: { type: DocType; reference: string }) => api.put<DocumentView[]>(`/api/v1/partner/hotels/${hotel.id}/documents/${v.type}`, { reference: v.reference }),
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
        <p className="muted small">Our team checks all four before your hotel goes live. Enter the document number or a link to the file. File upload is coming soon.</p>
        <ErrorBox error={submit.error} />
        {q.isLoading && <Loading />}
        {q.data?.map((d) => (
          <div key={d.type} className="row wrap" style={{ padding: '10px 0', borderTop: '1px solid var(--border)' }}>
            <div style={{ width: 190 }}><b>{DOC_LABEL[d.type]}</b></div>
            <span className={'badge ' + (d.verified ? 'ok' : d.submitted ? 'warn' : '')} style={{ width: 120, textAlign: 'center' }}>{d.verified ? '✓ Verified' : d.submitted ? 'Under review' : 'Not submitted'}</span>
            <input className="input grow" style={{ minWidth: 220 }} placeholder={d.reference ?? 'Document number or link'} value={draft[d.type] ?? ''} onChange={(e) => setDraft({ ...draft, [d.type]: e.target.value })} />
            <button className="btn soft sm" disabled={!draft[d.type]?.trim() || submit.isPending} onClick={() => submit.mutate({ type: d.type, reference: draft[d.type]!.trim() })}>{d.submitted ? 'Replace' : 'Submit'}</button>
          </div>
        ))}
      </div>
    </>
  );
}
