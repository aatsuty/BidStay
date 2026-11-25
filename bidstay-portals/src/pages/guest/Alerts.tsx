import { Link } from 'react-router-dom';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, ArrowRight, Bell, CalendarCheck2, Clock3, IndianRupee } from 'lucide-react';
import { ErrorBox } from '../../components/ui';
import { useApi } from '../../lib/api';
import { fmtDate, fmtDateTime, todayISO } from '../../lib/format';
import { useSession } from '../../lib/session';
import { useGuest } from './GuestShell';
import { rupees } from './models';

type Alert = { id: number; kind: 'OFFER' | 'BOOKING' | 'CANCELLATION' | 'PRICE_MATCH'; title: string; body: string;
  bidId?: number; bookingId?: number; searchId?: number; createdAt: string; read: boolean };
type Saved = { id: number; city: string; area: string; checkIn: string; checkOut: string; category: string;
  rooms: number; guests: number; budget: number; enabled: boolean };
const titleCase = (value: string) => value.replace(/\b\w/g, (letter) => letter.toUpperCase());

function searchLink(search: Saved | undefined) {
  if (!search) return '/';
  const { city, area, checkIn, checkOut, category, rooms, guests, budget } = search;
  return '/?' + new URLSearchParams({ city, area, checkIn, checkOut, category,
    rooms: String(rooms), guests: String(guests), budget: String(budget) });
}

export default function Alerts() {
  const { signedIn, openSignIn } = useGuest();
  const { cacheKey } = useSession();
  const api = useApi();
  const client = useQueryClient();
  const inbox = useInfiniteQuery({
    queryKey: ['guest-alerts', cacheKey],
    enabled: signedIn,
    initialPageParam: 0,
    queryFn: ({ pageParam }) => api.get<{ alerts: Alert[]; nextCursor?: number }>('/api/v1/guest/alerts' + (pageParam ? '?before=' + pageParam : '')),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    refetchOnWindowFocus: 'always',
    refetchInterval: 30_000,
  });
  const searches = useQuery({
    queryKey: ['saved-searches', cacheKey],
    enabled: signedIn,
    queryFn: () => api.get<Saved[]>('/api/v1/guest/saved-searches'),
  });
  function refreshAlerts() {
    void client.invalidateQueries({ queryKey: ['guest-alerts', cacheKey] });
    void client.invalidateQueries({ queryKey: ['guest-alert-count', cacheKey] });
  }
  const read = useMutation({
    mutationFn: (alertId?: number) => api.post('/api/v1/guest/alerts/read', { alertId }),
    onSuccess: refreshAlerts,
  });
  const change = useMutation({
    mutationFn: async ({ id, enabled }: { id: number; enabled?: boolean }) => {
      const path = '/api/v1/guest/saved-searches/' + id;
      if (enabled === undefined) await api.delete(path);
      else await api.put<Saved>(path, { enabled });
    },
    onSuccess: () => { void client.invalidateQueries({ queryKey: ['saved-searches', cacheKey] }); refreshAlerts(); },
  });
  const alerts = inbox.data?.pages.flatMap((page) => page.alerts);

  return <main className="guest-container guest-trip">
    <Link to="/" className="guest-back"><ArrowLeft size={16} /> Find a stay</Link>
    <h1>Alerts</h1>
    <p className="guest-trip-description">Keep track of hotel offers, bookings and saved stays.</p>
    {!signedIn ? <div className="guest-empty"><Bell size={23} /><h3>Sign in to see your alerts</h3><button className="guest-cta" onClick={openSignIn}>Sign in</button></div> : <>
      <ErrorBox error={searches.error ?? inbox.error ?? read.error ?? change.error} />
      <div className="guest-alert-layout">
        <section>
          <div className="guest-alert-heading"><h2>Your updates</h2>{alerts?.some((alert) => !alert.read) && <button className="guest-quiet" disabled={read.isPending} onClick={() => read.mutate(undefined)}>Mark all as read</button>}</div>
          {inbox.isLoading && <p role="status">Loading alerts…</p>}
          {alerts?.length === 0 && <div className="guest-empty"><Bell size={23} /><h3>You’re all caught up</h3><p>New offers and booking updates will appear here.</p></div>}
          <div className="guest-alert-list">{alerts?.map((alert) => {
            const target = alert.bookingId ? '/checkout/' + alert.bookingId : alert.bidId ? '/offers/' + alert.bidId : searchLink(searches.data?.find((search) => search.id === alert.searchId));
            const Icon = alert.kind === 'OFFER' || alert.kind === 'PRICE_MATCH' ? IndianRupee : CalendarCheck2;
            return <article className={'guest-alert-card' + (alert.read ? '' : ' unread')} key={alert.id}>
              <span className="guest-hotel-icon"><Icon size={18} /></span>
              <div><h3>{alert.title}</h3><p>{alert.body}</p><time dateTime={alert.createdAt}>{fmtDateTime(alert.createdAt)}</time>
                <Link to={target} onClick={() => { if (!alert.read) read.mutate(alert.id); }}>{alert.bookingId ? 'View booking' : alert.bidId ? 'View offer' : 'Find hotels'} <ArrowRight size={14} /></Link></div>
            </article>;
          })}</div>
          {inbox.hasNextPage && <button className="guest-quiet guest-more" onClick={() => { void inbox.fetchNextPage(); }} disabled={inbox.isFetchingNextPage}>Load more updates</button>}
        </section>
        <section>
          <div className="guest-alert-heading"><h2>Saved searches</h2></div>
          {searches.isLoading && <p role="status">Loading saved searches…</p>}
          {searches.data?.length === 0 && <div className="guest-empty"><Clock3 size={22} /><h3>No saved searches</h3><p>Set your budget and save a search to get alerts when hotels match.</p><Link to="/" className="guest-quiet guest-more">Find hotels</Link></div>}
          <div className="guest-alert-list">{searches.data?.map((search) => {
            const past = search.checkIn < todayISO();
            return <article key={search.id} className="guest-saved-card">
              <div><h3>{titleCase(search.area || search.city)}</h3><span className="guest-state">{past ? 'Past dates' : search.enabled ? 'Alerts on' : 'Paused'}</span></div>
              <p>{fmtDate(search.checkIn)} – {fmtDate(search.checkOut)} · {titleCase(search.category.toLowerCase())}</p>
              <p>{rupees.format(search.budget)} per room per night</p>
              <div className="guest-saved-actions"><Link className="guest-quiet" to={searchLink(search)}>Search again</Link>
                {!past && <button className="guest-quiet" disabled={change.isPending} onClick={() => change.mutate({ id: search.id, enabled: !search.enabled })}>{search.enabled ? 'Pause' : 'Resume'}</button>}
                <button className="guest-quiet" disabled={change.isPending} onClick={() => change.mutate({ id: search.id })}>Remove</button>
              </div>
            </article>;
          })}</div>
        </section>
      </div>
    </>}
  </main>;
}
