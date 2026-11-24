import { useState } from 'react';
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { ErrorBox, Loading, PageHead } from '../../components/ui';
import { useApi } from '../../lib/api';
import { fmtDateTime } from '../../lib/format';
import { useSession } from '../../lib/session';

type Status = 'PENDING' | 'PUBLISHED' | 'HIDDEN';
type Review = { bookingId: number; hotelName: string; rating: number; comment?: string; createdAt: string };

export default function Reviews() {
  const api = useApi();
  const client = useQueryClient();
  const { cacheKey } = useSession();
  const [status, setStatus] = useState<Status>('PENDING');
  const query = useInfiniteQuery({
    queryKey: ['admin-reviews', cacheKey, status], initialPageParam: 0,
    queryFn: ({ pageParam }) => api.get<Review[]>('/api/v1/admin/reviews?status=' + status + (pageParam ? '&before=' + pageParam : '')),
    getNextPageParam: (page) => page.length === 50 ? page.at(-1)?.bookingId : undefined,
  });
  const change = useMutation({
    mutationFn: ({ bookingId, decision }: { bookingId: number; decision: 'PUBLISHED' | 'HIDDEN' }) =>
      api.post(`/api/v1/admin/reviews/${bookingId}/${decision}`),
    onSuccess: () => { void client.invalidateQueries({ queryKey: ['admin-reviews', cacheKey] }); },
  });
  const reviews = query.data?.pages.flat() ?? [];
  return <>
    <PageHead title="Guest reviews" sub="Review submissions from guests whose paid stays have ended." />
    <div className="row wrap">
      {(['PENDING', 'PUBLISHED', 'HIDDEN'] as const).map((value) => <button key={value} type="button" aria-pressed={status === value}
        className={'btn ' + (status === value ? '' : 'ghost')} onClick={() => setStatus(value)}>{value[0] + value.slice(1).toLowerCase()}</button>)}
    </div>
    <ErrorBox error={query.error ?? change.error} />
    {query.isLoading && <Loading />}
    {query.isSuccess && reviews.length === 0 && <p className="card muted">No reviews in this list.</p>}
    {reviews.map((review) => <article className="card" key={review.bookingId}>
      <h2>{review.hotelName} · {review.rating} / 5</h2>
      <p className="muted small">Booking #{review.bookingId} · {fmtDateTime(review.createdAt)}</p>
      {review.comment && <p style={{ overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}>{review.comment}</p>}
      <div className="row wrap">
        {status !== 'PUBLISHED' && <button type="button" className="btn" disabled={change.isPending}
          onClick={() => change.mutate({ bookingId: review.bookingId, decision: 'PUBLISHED' })}>Publish</button>}
        {status !== 'HIDDEN' && <button type="button" className="btn ghost" disabled={change.isPending}
          onClick={() => change.mutate({ bookingId: review.bookingId, decision: 'HIDDEN' })}>Hide</button>}
      </div>
    </article>)}
    {query.hasNextPage && <button type="button" className="btn ghost" disabled={query.isFetchingNextPage}
      onClick={() => { void query.fetchNextPage(); }}>Load more reviews</button>}
  </>;
}
