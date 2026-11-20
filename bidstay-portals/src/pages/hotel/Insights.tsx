import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../lib/api';
import { fmtDuration, inr } from '../../lib/format';
import type { Dashboard } from '../../lib/types';
import { ErrorBox, Loading, PageHead } from '../../components/ui';
import { useHotel } from './HotelContext';

export default function Insights() {
  const { hotel } = useHotel();
  const api = useApi();
  const q = useQuery({ queryKey: ['dash', hotel.id], queryFn: () => api.get<Dashboard>(`/api/v1/partner/hotels/${hotel.id}/dashboard`), refetchInterval: 10000 });
  const d = q.data;
  const stats: [string | number, string][] = d ? [
    [d.pendingReplies, 'Waiting for your reply'], [d.bidsLast30Days, 'Bids received (30 days)'], [d.acceptedLast30Days, 'Accepted at guest price'],
    [d.counteredLast30Days, 'Counter offers'], [d.declinedOrExpiredLast30Days, 'Declined or no reply'], [fmtDuration(d.avgReplySeconds), 'Your average reply time'],
    [d.confirmedBookingsLast30Days, 'Paid bookings (30 days)'], [inr(d.payoutLast30Days), 'Your payout (30 days)'], [inr(d.commissionLast30Days), 'BidStay fee (30 days)'], [d.upcomingStays, 'Upcoming stays'],
  ] : [];
  return (
    <>
      <PageHead title="Insights" sub="Last 30 days. Fast replies rank you higher in bid notifications." />
      <ErrorBox error={q.error} />
      {q.isLoading && <Loading />}
      <div className="stats">{stats.map(([v, l]) => <div className="stat" key={l}><b>{v}</b><span>{l}</span></div>)}</div>
    </>
  );
}
