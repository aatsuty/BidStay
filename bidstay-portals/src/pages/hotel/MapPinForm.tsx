import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, useApi } from '../../lib/api';
import { useSession } from '../../lib/session';
import { ErrorBox, Loading } from '../../components/ui';
import LocationPin from './LocationPin';
import { parsePin, type Pin, type PinDraft } from './map-pin';

export default function MapPinForm({ hotelId }: { hotelId: number }) {
  const api = useApi();
  const client = useQueryClient();
  const { cacheKey } = useSession();
  const key = ['hotel-map-pin', cacheKey, hotelId];
  const endpoint = `/api/v1/partner/hotels/${hotelId}/map-pin`;
  const current = useQuery({ queryKey: key, queryFn: () => api.get<Pin>(endpoint), retry: false });
  const [draft, setDraft] = useState<PinDraft>();
  const value = draft ?? { latitude: String(current.data?.latitude ?? ''), longitude: String(current.data?.longitude ?? '') };
  const pin = parsePin(value);
  const save = useMutation({
    mutationFn: (value: Pin) => api.put<Pin>(endpoint, value),
    onSuccess: (location) => {
      client.setQueryData(key, location);
      setDraft(undefined);
    },
  });
  if (current.error instanceof ApiError && current.error.status === 403) return null;
  return <section className="card stack">
    <h2>Hotel map pin</h2>
    {current.isLoading && <Loading />}
    <ErrorBox error={current.error ?? save.error} />
    {current.data && <>
      <fieldset disabled={save.isPending} style={{ border: 0, margin: 0, padding: 0, minWidth: 0 }}>
        <LocationPin value={value} onChange={(next) => { if (!save.isPending) { setDraft(next); save.reset(); } }} />
      </fieldset>
      <button className="btn" type="button" disabled={!draft || !pin || save.isPending}
        onClick={() => { if (pin) save.mutate(pin); }}>Save map pin</button>
      {save.isSuccess && <p role="status">Map pin saved.</p>}
    </>}
  </section>;
}
