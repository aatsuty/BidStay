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
    {events.length > 0 && <div className="card table-wrap"><table>
      <thead><tr><th>When</th><th>Action</th><th>Actor</th><th>Record</th><th>Hotel</th></tr></thead>
      <tbody>{events.map((event) => <tr key={event.id}>
        <td><time dateTime={event.at}>{fmtDateTime(event.at)}</time></td>
        <td><strong>{label(event.action)}</strong></td>
        <td>{event.actorName ?? (event.actorId ? `User #${event.actorId}` : 'System')}</td>
        <td>{label(event.subjectType)} #{event.subjectId}</td>
        <td>{event.hotelId ? `#${event.hotelId}` : '—'}</td>
      </tr>)}</tbody>
    </table></div>}
    {history.hasNextPage && <button className="btn ghost" style={{ marginTop: 13 }} disabled={history.isFetchingNextPage}
      onClick={() => { void history.fetchNextPage(); }}>Load older events</button>}
  </>;
}
