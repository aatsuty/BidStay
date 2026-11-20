import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../lib/api';
import { useSession } from '../../lib/session';
import type { AdminStats } from '../../lib/types';
import { useMe } from '../../App';

export default function AdminShell() {
  const api = useApi();
  const nav = useNavigate();
  const { signOut } = useSession();
  const me = useMe();
  const stats = useQuery({ queryKey: ['adminStats'], queryFn: () => api.get<AdminStats>('/api/v1/admin/stats'), refetchInterval: 15000 });
  const links: [string, string, number?, boolean?][] = [
    ['/admin', '📊 Overview', undefined, true], ['/admin/hotels', '🏨 Hotels', stats.data?.pendingHotels],
    ['/admin/bids', '🔨 Bids'], ['/admin/bookings', '🧾 Bookings & commission'],
  ];
  return (
    <div className="shell">
      <aside className="side">
        <div className="logo">Bid<b>Stay</b></div>
        <div className="portal-tag">Admin</div>
        {links.map(([to, label, n, end]) => (
          <NavLink key={to} to={to} end={end} className={({ isActive }) => 'nav' + (isActive ? ' active' : '')}>
            {label}{n ? <span className="count">{n}</span> : null}
          </NavLink>
        ))}
        <div className="foot">
          <div className="small">{me.data?.name}</div>
          <button className="btn ghost sm" style={{ marginTop: 6 }} onClick={() => { signOut(); nav('/login'); }}>Sign out</button>
        </div>
      </aside>
      <main className="main"><Outlet /></main>
    </div>
  );
}
