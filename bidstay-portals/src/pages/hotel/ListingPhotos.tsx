import { useRef, useState, type FormEvent } from 'react';
import { Camera, ImagePlus } from 'lucide-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ErrorBox, Loading, PageHead } from '../../components/ui';
import { useApi } from '../../lib/api';
import { useSession } from '../../lib/session';
import PhotoPreview, { type ReviewPhoto } from '../media/PhotoPreview';
import { useHotel } from './HotelContext';

export default function ListingPhotos() {
  const { hotel } = useHotel();
  return <Gallery key={hotel.id} hotelId={hotel.id} hotelName={hotel.name} />;
}

function Gallery({ hotelId, hotelName }: { hotelId: number; hotelName: string }) {
  const api = useApi();
  const qc = useQueryClient();
  const { cacheKey } = useSession();
  const fileInput = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File>();
  const [room, setRoom] = useState('');
  const [caption, setCaption] = useState('');
  const endpoint = `/api/v1/partner/hotels/${hotelId}/media`;
  const key = ['listing-media', cacheKey, hotelId];
  const gallery = useQuery({
    queryKey: key,
    queryFn: () => api.get<{ rooms: { id: number; name: string }[]; photos: ReviewPhoto[] }>(endpoint),
    refetchOnWindowFocus: 'always',
  });
  const upload = useMutation({
    mutationFn: (data: FormData) => api.post(endpoint, data),
    onSuccess: () => { if (fileInput.current) fileInput.current.value = ''; setFile(undefined); setCaption(''); void qc.invalidateQueries({ queryKey: key }); },
  });
  const withdraw = useMutation({
    mutationFn: (id: number) => api.post(endpoint + '/' + id + '/withdraw'),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: key }); },
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!file) return;
    const data = new FormData();
    data.append('file', file);
    if (room) data.append('roomTypeId', room);
    if (caption.trim()) data.append('caption', caption.trim());
    upload.mutate(data);
  }

  return <>
    <PageHead title="Listing photos" sub={hotelName} />
    <form className="card listing-upload" onSubmit={submit}>
      <h2><ImagePlus size={20} /> Add a photo</h2>
      <p className="muted small">Approved JPEG or PNG photos appear in guest search and the hotel gallery. Files must be under 5 MB.</p>
      <div className="listing-upload-fields">
        <label className="field">Show under<select className="input" value={room} onChange={(e) => setRoom(e.target.value)}>
          <option value="">Hotel gallery</option>{gallery.data?.rooms.map((type) => <option key={type.id} value={type.id}>{type.name}</option>)}
        </select></label>
        <label className="field">Caption <span className="muted">(optional)</span><input className="input" value={caption} maxLength={100} onChange={(e) => setCaption(e.target.value)} placeholder="e.g. Deluxe double room" /></label>
        <label className="field">Photo<input ref={fileInput} className="input" type="file" required accept="image/jpeg,image/png" onChange={(e) => setFile(e.target.files?.[0])} /></label>
      </div>
      <ErrorBox error={gallery.error ?? upload.error ?? withdraw.error} />
      <button className="btn" disabled={!file || upload.isPending}>{upload.isPending ? 'Uploading…' : 'Upload for review'}</button>
    </form>
    {gallery.isLoading && <Loading />}
    {gallery.data?.photos.length === 0 && <div className="listing-empty"><Camera size={26} /><h3>No listing photos</h3><p className="muted small">Start with the exterior and at least one photo of each room type.</p></div>}
    <div className="listing-grid">{gallery.data?.photos.map((photo) => (
      <article key={photo.id} className="listing-card">
        <PhotoPreview endpoint={endpoint + '/' + photo.id + '/preview'} alt={photo.caption ?? photo.roomName ?? 'Property photo'} />
        <div className="listing-card-copy">
          <div className="row wrap spread"><b>{photo.roomName ?? 'Hotel gallery'}</b><span className={'badge ' + (photo.status === 'APPROVED' ? 'ok' : photo.status === 'PENDING' ? 'warn' : 'bad')}>
            {photo.status === 'APPROVED' ? 'Approved' : photo.status === 'PENDING' ? 'Under review' : 'Rejected'}
          </span></div>
          {photo.caption && <p className="muted small">{photo.caption}</p>}
          {photo.reviewReason && <p className="muted small">{photo.reviewReason}</p>}
          <button className="btn ghost sm" disabled={withdraw.isPending} onClick={() => {
            if (window.confirm('Remove this photo from the listing?')) withdraw.mutate(photo.id);
          }}>Remove photo</button>
        </div>
      </article>
    ))}</div>
  </>;
}
