import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../lib/api';
import { fmtDate, fmtDateTime, inr } from '../../lib/format';
import type { AdminBooking } from '../../lib/types';
import { ErrorBox, Loading, PageHead } from '../../components/ui';

const tone: Record<string, string> = { CONFIRMED: 'ok', PENDING_PAYMENT: 'warn', EXPIRED: '', CANCELLED: '' };

export default function AdminBookings() {
  const api = useApi();
  const q = useQuery({ queryKey: ['adminBookings'], queryFn: () => api.get<AdminBooking[]>('/api/v1/admin/bookings'), refetchInterval: 15000 });
  const paid = (q.data ?? []).filter((b) => b.status === 'CONFIRMED');
  return (
    <>
      <PageHead title="Bookings & commission" sub="Latest 100 bookings" />
      <ErrorBox error={q.error} />
      {q.isLoading && <Loading />}
      <div className="stats">
        <div className="stat"><b>{paid.length}</b><span>Paid bookings (in this list)</span></div>
        <div className="stat"><b>{inr(paid.reduce((s, b) => s + b.total, 0))}</b><span>Booking value</span></div>
        <div className="stat"><b>{inr(paid.reduce((s, b) => s + b.commission, 0))}</b><span>Commission</span></div>
      </div>
      <div className="card"><div className="table-wrap"><table>
        <thead><tr><th>#</th><th>Hotel</th><th>Guest</th><th>Stay</th><th>Status</th><th className="right">Total</th><th className="right">Commission</th><th>Created</th></tr></thead>
        <tbody>{q.data?.map((b) => (
          <tr key={b.id}><td>{b.id}</td><td><b>{b.hotel}</b></td><td>{b.guest}</td><td>{fmtDate(b.checkIn)} → {fmtDate(b.checkOut)}<div className="muted small">{b.rooms} room{b.rooms > 1 ? 's' : ''}</div></td>
            <td><span className={'badge ' + (tone[b.status] ?? '')}>{b.status.replace('_', ' ').toLowerCase()}</span></td>
            <td className="right">{inr(b.total)}</td><td className="right">{inr(b.commission)}</td><td className="muted small">{fmtDateTime(b.createdAt)}</td></tr>))}
          {q.data && !q.data.length && <tr><td colSpan={8} className="muted">No bookings yet.</td></tr>}</tbody>
      </table></div></div>
    </>
  );
}
