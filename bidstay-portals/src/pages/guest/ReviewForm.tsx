import { useMutation } from '@tanstack/react-query';
import { ErrorBox } from '../../components/ui';
import { useApi } from '../../lib/api';
import type { Booking } from './models';

export default function ReviewForm({ bookingId, status, onSubmitted }: {
  bookingId: number; status: NonNullable<Booking['reviewStatus']>; onSubmitted: () => void;
}) {
  const api = useApi();
  const submit = useMutation({
    mutationFn: (body: { rating: number; comment: string }) => api.post(`/api/v1/bookings/${bookingId}/review`, body),
    onSuccess: onSubmitted,
  });
  return <section className="guest-checkout">
    <h2>Review your stay</h2>
    <ErrorBox error={submit.error} />
    {status === 'ELIGIBLE' && !submit.isSuccess ? <form className="stack" onSubmit={(event) => {
      event.preventDefault();
      const form = new FormData(event.currentTarget);
      submit.mutate({ rating: Number(form.get('rating')), comment: String(form.get('comment')) });
    }}>
      <label className="field">Rating out of 5<select className="input" name="rating" required defaultValue="">
        <option value="">Choose a rating</option>{[5, 4, 3, 2, 1].map((rating) => <option key={rating}>{rating}</option>)}
      </select></label>
      <label className="field">Comment (optional)<textarea className="input" name="comment" maxLength={500} rows={3} /></label>
      <button className="guest-cta" disabled={submit.isPending}>Submit review</button>
    </form> : <p role="status">{status === 'PUBLISHED' ? 'Your review is published.'
      : status === 'HIDDEN' ? 'Your review is hidden.' : 'Your review is awaiting moderation.'}</p>}
  </section>;
}
