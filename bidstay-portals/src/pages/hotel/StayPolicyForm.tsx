import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, useApi } from '../../lib/api';
import { useSession } from '../../lib/session';
import { ErrorBox, Loading } from '../../components/ui';
import type { StayPolicy } from '../../lib/types';

export default function StayPolicyForm({ hotelId }: { hotelId: number }) {
  const api = useApi();
  const client = useQueryClient();
  const { cacheKey } = useSession();
  const path = `/api/v1/partner/hotels/${hotelId}/stay-policy`;
  const key = ['property-stay-policy', cacheKey, hotelId];
  const policy = useQuery({ queryKey: key, queryFn: () => api.get<StayPolicy>(path), retry: false });
  const save = useMutation({
    mutationFn: (values: FormData) => api.put<StayPolicy>(path, {
      checkInFrom: values.get('checkInFrom'),
      checkOutBy: values.get('checkOutBy'),
      guestInstructions: values.get('guestInstructions'),
    }),
    onSuccess: (updated) => { client.setQueryData(key, updated); },
  });

  if (policy.error instanceof ApiError && policy.error.status === 403) return null;
  return <section className="card">
    <h2>Stay policy</h2>
    <p className="muted small">Guests see these terms before paying. Edits apply to future checkouts.</p>
    <ErrorBox error={policy.error ?? save.error} />
    {policy.isLoading && <Loading />}
    {policy.data && <form className="stack" onSubmit={(event) => {
      event.preventDefault();
      save.mutate(new FormData(event.currentTarget));
    }}>
      <div className="row wrap">
        <label className="field">Check-in from (IST)
          <input className="input" type="time" name="checkInFrom" required defaultValue={policy.data.checkInFrom ?? ''} />
        </label>
        <label className="field">Check-out by (IST)
          <input className="input" type="time" name="checkOutBy" required defaultValue={policy.data.checkOutBy ?? ''} />
        </label>
      </div>
      <label className="field">Arrival instructions or guest requirements
        <textarea className="input" name="guestInstructions" rows={3} maxLength={1000}
          defaultValue={policy.data.guestInstructions ?? ''} />
      </label>
      <div className="row wrap">
        <button className="btn" disabled={save.isPending}>Save stay policy</button>
        {save.isSuccess && <span role="status">Stay policy saved.</span>}
      </div>
    </form>}
  </section>;
}
