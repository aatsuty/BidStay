import { useState } from 'react';
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Images } from 'lucide-react';
import { ErrorBox, Loading, PageHead } from '../../components/ui';
import { useApi } from '../../lib/api';
import { useSession } from '../../lib/session';
import PhotoPreview, { type ReviewPhoto } from '../media/PhotoPreview';

export default function ListingMediaReview() {
  const api = useApi();
  const { cacheKey } = useSession();
  const query = useInfiniteQuery({
    queryKey: ['listing-review', cacheKey],
    initialPageParam: 0,
    queryFn: ({ pageParam }) => api.get<{ photos: ReviewPhoto[]; nextCursor?: number }>(
      '/api/v1/admin/media' + (pageParam ? '?before=' + pageParam : '')),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    refetchOnWindowFocus: 'always',
  });
  const photos = query.data?.pages.flatMap((page) => page.photos);
  return <>
    <PageHead title="Listing photo review" sub="Approve photos that accurately show the hotel or named room type." />
    <ErrorBox error={query.error} />
    {query.isLoading && <Loading />}
    {photos?.length === 0 && <div className="listing-empty"><Images size={26} /><h3>No photos waiting for review</h3></div>}
    <div className="listing-grid">{photos?.map((photo) => <ReviewCard key={photo.id} photo={photo} />)}</div>
    {query.hasNextPage && <button className="btn ghost" style={{ marginTop: 12 }} onClick={() => { void query.fetchNextPage(); }} disabled={query.isFetchingNextPage}>Load more photos</button>}
  </>;
}

function ReviewCard({ photo }: { photo: ReviewPhoto }) {
  const api = useApi();
  const qc = useQueryClient();
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const review = useMutation({
    mutationFn: (approved: boolean) => api.post(`/api/v1/admin/media/${photo.id}/review`, { approved, reason }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['listing-review'] }); },
  });
  return <article className="listing-card">
    <PhotoPreview endpoint={`/api/v1/admin/media/${photo.id}/preview`} alt={photo.caption ?? photo.roomName ?? photo.hotelName} />
    <div className="listing-card-copy">
      <h2>{photo.hotelName}</h2><p className="muted small">{photo.roomName ?? 'Hotel gallery'}{photo.caption ? ' · ' + photo.caption : ''}</p>
      <ErrorBox error={review.error} />
      {rejecting ? <form className="stack" onSubmit={(event) => { event.preventDefault(); review.mutate(false); }}>
        <label className="field">Reason<input autoFocus className="input" required maxLength={180} value={reason} onChange={(e) => setReason(e.target.value)} /></label>
        <div className="row wrap"><button className="btn danger" disabled={review.isPending || !reason.trim()}>Reject photo</button><button type="button" className="btn ghost" onClick={() => setRejecting(false)}>Back</button></div>
      </form> : <div className="row wrap">
        <button className="btn ok" disabled={review.isPending} onClick={() => review.mutate(true)}>Approve photo</button>
        <button className="btn ghost" disabled={review.isPending} onClick={() => setRejecting(true)}>Reject…</button>
      </div>}
    </div>
  </article>;
}
