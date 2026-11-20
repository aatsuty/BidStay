import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../lib/api';
import { fmtDate, fmtDateTime, inr } from '../../lib/format';
import type { AdminBid } from '../../lib/types';
import { ErrorBox, Loading, PageHead } from '../../components/ui';

const tone: Record<string, string> = { OPEN: 'info', HELD: 'warn', BOOKED: 'ok', EXPIRED: '', CANCELLED: '' };

export default function AdminBids() {
  const api = useApi();
  const q = useQuery({ queryKey: ['adminBids'], queryFn: () => api.get<AdminBid[]>('/api/v1/admin/bids'), refetchInterval: 10000 });
  return (
    <>
      <PageHead title="Bids" sub="Latest 100 guest bids, to see what guests offer and how hotels respond" />
      <ErrorBox error={q.error} />
      {q.isLoading && <Loading />}
      <div className="card"><div className="table-wrap"><table>
        <thead><tr><th>#</th><th>Guest</th><th>Stay</th><th className="right">Bid / night</th><th>Round</th><th>Hotels asked</th><th>Offers ready</th><th>Status</th><th>Sent</th></tr></thead>
        <tbody>{q.data?.map((b) => (
          <tr key={b.id}><td>{b.id}</td><td>{b.guest}</td><td>{fmtDate(b.checkIn)} → {fmtDate(b.checkOut)}<div className="muted small">{b.rooms} room{b.rooms > 1 ? 's' : ''} · {b.category.toLowerCase()}</div></td>
            <td className="right bold">{inr(b.pricePerNight)}</td><td>{b.round}</td><td>{b.offers}</td><td>{b.offersReady}</td>
            <td><span className={'badge ' + (tone[b.status] ?? '')}>{b.status.toLowerCase()}</span></td><td className="muted small">{fmtDateTime(b.createdAt)}</td></tr>))}
          {q.data && !q.data.length && <tr><td colSpan={9} className="muted">No bids yet.</td></tr>}</tbody>
      </table></div></div>
    </>
  );
}
