import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ErrorBox, Loading, PageHead } from '../../components/ui';
import { useApi } from '../../lib/api';
import { fmtDuration } from '../../lib/format';
import { useSession } from '../../lib/session';

interface Stats {
  liveHotels: number; bidsReached: number; usefulOffers: number; medianFirstOfferSeconds?: number;
  paidBids: number; bookedRoomHours: number; bookingsWithoutTimes: number;
}

export default function Market() {
  const api = useApi();
  const { cacheKey } = useSession();
  const [cohort, setCohort] = useState({ city: 'Delhi', area: 'Paharganj' });
  const query = useQuery({
    queryKey: ['admin-market', cacheKey, cohort],
    queryFn: () => api.get<Stats>('/api/v1/admin/market?' + new URLSearchParams(cohort)),
  });
  const stats = query.data;

  function update(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setCohort({ city: String(data.get('city')).trim(), area: String(data.get('area')).trim() });
  }

  return <>
    <PageHead title="Neighbourhood pilot" sub="Compare market activity over the last 30 days." />
    <form className="card row wrap" onSubmit={update} style={{ alignItems: 'end' }}>
      <label className="field">City<input className="input" name="city" required maxLength={80} defaultValue={cohort.city} /></label>
      <label className="field">Neighbourhood<input className="input" name="area" required maxLength={100} defaultValue={cohort.area} /></label>
      <button className="btn">View activity</button>
    </form>
    <ErrorBox error={query.error} />
    {query.isLoading && <Loading />}
    {stats && <section>
      <h2>{cohort.area}, {cohort.city}</h2>
      <div className="stats">
        <div className="stat"><b>{stats.liveHotels}</b><span>Live hotels</span></div>
        <div className="stat"><b>{stats.bidsReached}</b><span>Bids reaching the area</span></div>
        <div className="stat"><b>{stats.bidsReached ? (stats.usefulOffers / stats.bidsReached).toFixed(2) : '—'}</b><span>Useful offers per bid</span></div>
        <div className="stat"><b>{fmtDuration(stats.medianFirstOfferSeconds == null ? undefined : Math.round(stats.medianFirstOfferSeconds))}</b><span>Median time to first offer</span></div>
        <div className="stat"><b>{stats.bidsReached ? `${(100 * stats.paidBids / stats.bidsReached).toFixed(1)}%` : '—'}</b><span>Paid bid conversion</span></div>
        <div className="stat"><b>{Math.round(stats.bookedRoomHours).toLocaleString('en-IN')}</b><span>Booked room-hours</span></div>
      </div>
      {stats.bookingsWithoutTimes > 0 && <p className="muted small">{stats.bookingsWithoutTimes} bookings lack recorded arrival and departure times.</p>}
    </section>}
  </>;
}
