import RecordCell from '../../components/RecordCell';
import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../lib/api';
import { fmtDate, fmtDateTime, fmtIndiaTime, inr } from '../../lib/format';
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
      <div className="card"><div className="table-wrap"><table className="record-table" role="table">
        <thead role="rowgroup"><tr role="row"><th role="columnheader" scope="col">#</th><th role="columnheader" scope="col">Hotel</th><th role="columnheader" scope="col">Guest</th><th role="columnheader" scope="col">Stay</th><th role="columnheader" scope="col">Status</th><th role="columnheader" scope="col" className="right">Total</th><th role="columnheader" scope="col" className="right">Commission</th><th role="columnheader" scope="col">Created</th></tr></thead>
        <tbody role="rowgroup">{q.data?.map((b) => (
          <tr role="row" key={b.id}><RecordCell label="Booking ID">{b.id}</RecordCell><RecordCell label="Hotel"><b>{b.hotel}</b></RecordCell><RecordCell label="Guest">{b.guest}</RecordCell><RecordCell label="Stay">{b.arrival && b.departure
            ? `${fmtDate(b.checkIn)} · ${fmtIndiaTime(b.arrival)}–${fmtIndiaTime(b.departure)} IST`
            : `${fmtDate(b.checkIn)} → ${fmtDate(b.checkOut)}`}<div className="muted small">{b.rooms} room{b.rooms > 1 ? 's' : ''}</div></RecordCell>
            <RecordCell label="Status"><span className={'badge ' + (tone[b.status] ?? '')}>{b.status.replace('_', ' ').toLowerCase()}</span></RecordCell>
            <RecordCell label="Total" className="right">{inr(b.total)}</RecordCell><RecordCell label="Commission" className="right">{inr(b.commission)}</RecordCell><RecordCell label="Created" className="muted small">{fmtDateTime(b.createdAt)}</RecordCell></tr>))}
          {q.data && !q.data.length && <tr role="row"><td role="cell" colSpan={8} className="muted">No bookings yet.</td></tr>}</tbody>
      </table></div></div>
    </>
  );
}
