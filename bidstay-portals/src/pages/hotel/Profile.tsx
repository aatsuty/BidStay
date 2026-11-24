import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../lib/api';
import type { DocType, DocumentView } from '../../lib/types';
import { DOC_LABEL, ErrorBox, HotelStatusBadge, Loading, PageHead } from '../../components/ui';
import { useHotel } from './HotelContext';
import StaffAccess from './StaffAccess';
import StayPolicyForm from './StayPolicyForm';

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
        <p className="muted small">Upload PDF, JPEG or PNG files up to 5 MB. Replacing an approved document sends the hotel back for review. Upload guest-facing images in <Link to="../photos">Listing photos</Link>.</p>
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
      <StayPolicyForm key={`policy-${hotel.id}`} hotelId={hotel.id} />
      <StaffAccess key={`staff-${hotel.id}`} hotelId={hotel.id} />
    </>
  );
}
