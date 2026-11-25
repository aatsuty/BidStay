import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, useApi } from '../../lib/api';
import { useSession } from '../../lib/session';
import { ErrorBox, Loading } from '../../components/ui';

interface RoomRates { roomTypeId: number; name: string; enabled: boolean; threeHours?: number; sixHours?: number }

export default function HourlyRates({ hotelId }: { hotelId: number }) {
  const { cacheKey } = useSession();
  const api = useApi();
  const endpoint = `/api/v1/partner/hotels/${hotelId}/hourly`;
  const key = ['partner-hourly', cacheKey, hotelId];
  const rates = useQuery({ queryKey: key, queryFn: () => api.get<RoomRates[]>(endpoint), retry: false });
  if (rates.error instanceof ApiError && rates.error.status === 403) return <section className="card">
    <h2>Hourly stays</h2><p className="muted small">Ask this property's manager to publish or change hourly rates.</p>
  </section>;
  return <section className="card">
    <h2>Hourly stays</h2>
    <p className="muted small">Publish 3- and 6-hour rates per room. Stays run between 08:00 and 22:00 IST with 30 minutes between bookings.</p>
    <ErrorBox error={rates.error} />
    {rates.isLoading && <Loading />}
    {rates.data?.map((room) => <RateForm key={room.roomTypeId} room={room} endpoint={endpoint}
      refreshKey={key} />)}
  </section>;
}

function RateForm({ room, endpoint, refreshKey }: { room: RoomRates; endpoint: string; refreshKey: readonly unknown[] }) {
  const [enabled, setEnabled] = useState(room.enabled);
  const [threeHours, setThreeHours] = useState(String(room.threeHours ?? ''));
  const [sixHours, setSixHours] = useState(String(room.sixHours ?? ''));
  const api = useApi();
  const client = useQueryClient();
  const update = useMutation({
    mutationFn: () => api.put(`${endpoint}/${room.roomTypeId}`, {
      enabled, threeHours: threeHours || null, sixHours: sixHours || null,
    }),
    onSuccess: () => { void client.invalidateQueries({ queryKey: refreshKey }); },
  });

  return <form className="stack" onSubmit={(event) => { event.preventDefault(); update.mutate(); }}>
    <h3>{room.name}</h3>
    <div className="row wrap">
      <label className="toggle"><input type="checkbox" checked={enabled} onChange={(e) => { update.reset(); setEnabled(e.target.checked); }} />
        Offer hourly stays</label>
      {enabled && <>
        <label className="field">3-hour rate (₹)<input className="input" type="number" min=".01" max="999999999.99" step=".01"
          required value={threeHours} onChange={(e) => { update.reset(); setThreeHours(e.target.value); }} /></label>
        <label className="field">6-hour rate (₹)<input className="input" type="number" min=".01" max="999999999.99" step=".01"
          required value={sixHours} onChange={(e) => { update.reset(); setSixHours(e.target.value); }} /></label>
      </>}
      <button className="btn" disabled={update.isPending}>Save hourly rates</button>
      {update.isSuccess && <span role="status">Hourly rates saved.</span>}
    </div>
    <ErrorBox error={update.error} />
  </form>;
}
