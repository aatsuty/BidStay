import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../lib/api';
import { inr } from '../../lib/format';
import type { AdminStats } from '../../lib/types';
import { ErrorBox, Loading, PageHead } from '../../components/ui';

export default function Overview() {
  const api = useApi();
  const q = useQuery({ queryKey: ['adminStats'], queryFn: () => api.get<AdminStats>('/api/v1/admin/stats'), refetchInterval: 15000 });
  const s = q.data;
  return (
    <>
      <PageHead title="Overview" sub="Live numbers for the pilot" />
      <ErrorBox error={q.error} />
      {q.isLoading && <Loading />}
      {s && (
        <>
          {s.pendingHotels > 0 && <div className="notice">{s.pendingHotels} hotel{s.pendingHotels > 1 ? 's are' : ' is'} waiting for approval. <Link to="/admin/hotels">Review now →</Link></div>}
          <div className="stats">
            {([[s.liveHotels, 'Live hotels'], [s.pendingHotels, 'Awaiting approval'], [s.totalBids, 'Bids sent'], [s.confirmedBookings, 'Paid bookings'],
              [inr(s.bookingValue), 'Booking value'], [inr(s.commissionEarned), 'Commission earned']] as [string | number, string][]).map(([v, l]) => (
              <div className="stat" key={l}><b>{v}</b><span>{l}</span></div>))}
          </div>
          <div className="card">
            <h2>Pilot targets (from the business plan)</h2>
            <p className="muted small">The 30-45 day test decides whether to build the full launch. These are measured on real bids.</p>
            {s.targets.map((t) => {
              const has = t.actualPct != null;
              const hit = has && t.actualPct! >= t.targetPct;
              return (
                <div key={t.key} style={{ margin: '18px 0' }}>
                  <div className="row spread">
                    <b>{t.label}</b>
                    <span><b>{has ? `${t.actualPct}%` : 'no data yet'}</b> <span className="muted">target {t.targetPct}%</span> {has && <span className={'badge ' + (hit ? 'ok' : 'warn')}>{hit ? 'On track' : 'Below target'}</span>}</span>
                  </div>
                  <div className="bar"><i className={hit ? 'ok' : ''} style={{ width: `${Math.min(100, t.actualPct ?? 0)}%` }} /><u style={{ left: `${t.targetPct}%` }} /></div>
                  <div className="muted small">{t.basis}</div>
                </div>);
            })}
          </div>
        </>)}
    </>
  );
}
