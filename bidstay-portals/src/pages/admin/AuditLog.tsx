import RecordCell from '../../components/RecordCell';
import { useInfiniteQuery } from '@tanstack/react-query';
import { ErrorBox, Loading, PageHead } from '../../components/ui';
import { useApi } from '../../lib/api';
import { fmtDateTime } from '../../lib/format';
import { useSession } from '../../lib/session';

interface AuditEvent {
  id: number;
  action: string;
  subjectType: string;
  subjectId: number;
  hotelId?: number;
  actorId?: number;
  actorName?: string;
  at: string;
}
const label = (value: string) => value.toLowerCase().replaceAll('_', ' ').replace(/^./, (letter) => letter.toUpperCase());

export default function AuditLog() {
  const api = useApi();
  const { cacheKey } = useSession();
  const history = useInfiniteQuery({
    queryKey: ['audit-events', cacheKey],
    initialPageParam: 0,
    queryFn: ({ pageParam }) => api.get<{ events: AuditEvent[]; nextCursor?: number }>(
      '/api/v1/admin/audit' + (pageParam ? '?before=' + pageParam : '')),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    refetchOnWindowFocus: 'always',
  });
  const events = history.data?.pages.flatMap((page) => page.events) ?? [];
  return <>
    <PageHead title="Audit log" sub="Staff access, hotel approvals, admin sign-ins, and booking refunds." />
    <ErrorBox error={history.error} />
    {history.isLoading && <Loading />}
    {history.isSuccess && events.length === 0 && <div className="card muted">No audit events yet.</div>}
    {events.length > 0 && <div className="card table-wrap"><table className="record-table" role="table">
      <thead role="rowgroup"><tr role="row"><th role="columnheader" scope="col">When</th><th role="columnheader" scope="col">Action</th><th role="columnheader" scope="col">Actor</th><th role="columnheader" scope="col">Record</th><th role="columnheader" scope="col">Hotel</th></tr></thead>
      <tbody role="rowgroup">{events.map((event) => <tr role="row" key={event.id}>
        <RecordCell label="When"><time dateTime={event.at}>{fmtDateTime(event.at)}</time></RecordCell>
        <RecordCell label="Action"><strong>{label(event.action)}</strong></RecordCell>
        <RecordCell label="Actor">{event.actorName ?? (event.actorId ? `User #${event.actorId}` : 'System')}</RecordCell>
        <RecordCell label="Record">{label(event.subjectType)} #{event.subjectId}</RecordCell>
        <RecordCell label="Hotel">{event.hotelId ? `#${event.hotelId}` : '—'}</RecordCell>
      </tr>)}</tbody>
    </table></div>}
    {history.hasNextPage && <button className="btn ghost" style={{ marginTop: 13 }} disabled={history.isFetchingNextPage}
      onClick={() => { void history.fetchNextPage(); }}>Load older events</button>}
  </>;
}
