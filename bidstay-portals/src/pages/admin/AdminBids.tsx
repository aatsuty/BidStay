import RecordCell from '../../components/RecordCell';
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
      <div className="card"><div className="table-wrap"><table className="record-table" role="table">
        <thead role="rowgroup"><tr role="row"><th role="columnheader" scope="col">#</th><th role="columnheader" scope="col">Guest</th><th role="columnheader" scope="col">Stay</th><th role="columnheader" scope="col" className="right">Bid / night</th><th role="columnheader" scope="col">Round</th><th role="columnheader" scope="col">Hotels asked</th><th role="columnheader" scope="col">Offers ready</th><th role="columnheader" scope="col">Status</th><th role="columnheader" scope="col">Sent</th></tr></thead>
        <tbody role="rowgroup">{q.data?.map((b) => (
          <tr role="row" key={b.id}><RecordCell label="Bid ID">{b.id}</RecordCell><RecordCell label="Guest">{b.guest}</RecordCell><RecordCell label="Stay">{fmtDate(b.checkIn)} → {fmtDate(b.checkOut)}<div className="muted small">{b.rooms} room{b.rooms > 1 ? 's' : ''} · {b.category.toLowerCase()}</div></RecordCell>
            <RecordCell label="Bid / night" className="right bold">{inr(b.pricePerNight)}</RecordCell><RecordCell label="Round">{b.round}</RecordCell><RecordCell label="Hotels asked">{b.offers}</RecordCell><RecordCell label="Offers ready">{b.offersReady}</RecordCell>
            <RecordCell label="Status"><span className={'badge ' + (tone[b.status] ?? '')}>{b.status.toLowerCase()}</span></RecordCell><RecordCell label="Sent" className="muted small">{fmtDateTime(b.createdAt)}</RecordCell></tr>))}
          {q.data && !q.data.length && <tr role="row"><td role="cell" colSpan={9} className="muted">No bids yet.</td></tr>}</tbody>
      </table></div></div>
    </>
  );
}
