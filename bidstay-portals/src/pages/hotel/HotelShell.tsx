import { useState } from 'react';
import { Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../lib/api';
import { useSession } from '../../lib/session';
import type { HotelOffer, MyHotel } from '../../lib/types';
import { HotelStatusBadge, Loading } from '../../components/ui';
import { useMe } from '../../App';
import { HotelContext } from './HotelContext';

const KEY = 'bidstay.portal.hotel';

export default function HotelShell() {
  const api = useApi();
  const nav = useNavigate();
  const loc = useLocation();
  const { token, signOut } = useSession();
  const qc = useQueryClient();
  async function leave() {
    try { if (token) await api.post('/api/v1/auth/logout'); }
    finally {
      qc.clear();
      signOut();
      nav('/login');
    }
  }
  const me = useMe();
  const [picked, setPicked] = useState<number | null>(() => Number(localStorage.getItem(KEY)) || null);

  const hotelsQ = useQuery({ queryKey: ['partnerHotels'], queryFn: () => api.get<MyHotel[]>('/api/v1/partner/hotels') });
  const hotels = hotelsQ.data ?? [];
  const hotel = hotels.find((h) => h.id === picked) ?? hotels[0];

  const offersQ = useQuery({
    queryKey: ['offers', hotel?.id],
    queryFn: () => api.get<HotelOffer[]>(`/api/v1/partner/hotels/${hotel!.id}/offers`),
    enabled: !!hotel,
    refetchInterval: 5000,
  });

  if (hotelsQ.isLoading) return <div className="main"><Loading /></div>;
  if (!hotel) return loc.pathname.endsWith('/register') ? <RegisterOnly /> : <Navigate to="/hotel/register" replace />;

  const select = (id: number) => { localStorage.setItem(KEY, String(id)); setPicked(id); nav('/hotel/requests'); };
  const pending = offersQ.data?.length ?? 0;
  const links: [string, string, number?][] = [
    ['requests', '💬 Requests', pending], ['rates', '₹ Rates & rules'], ['availability', '📅 Availability'],
    ['bookings', '🧾 Bookings'], ['insights', '📈 Insights'], ['profile', '🏨 Profile & documents'],
  ];

  return (
    <HotelContext.Provider value={{ hotel, hotels, select }}>
      <div className="shell">
        <aside className="side">
          <div className="logo">Bid<b>Stay</b></div>
          <div className="portal-tag">Hotel partner</div>
          {links.map(([to, label, n]) => (
            <NavLink key={to} to={to} className={({ isActive }) => 'nav' + (isActive ? ' active' : '')}>
              {label}{n ? <span className="count">{n}</span> : null}
            </NavLink>
          ))}
          <div className="foot">
            <div className="small" style={{ marginBottom: 6, color: '#94a3b8' }}>Hotel</div>
            <select className="input" value={hotel.id} onChange={(e) => select(Number(e.target.value))}>
              {hotels.map((h) => <option key={h.id} value={h.id}>{h.name}</option>)}
            </select>
            <div style={{ marginTop: 8 }}><NavLink to="register" className="small" style={{ color: '#a5b4fc' }}>+ Register another hotel</NavLink></div>
            <div style={{ marginTop: 14 }} className="small">{me.data?.name}</div>
            <button className="btn ghost sm" style={{ marginTop: 6 }} onClick={() => { void leave().catch(() => undefined); }}>Sign out</button>
          </div>
        </aside>
        <main className="main">
          {hotel.status !== 'ACTIVE' && (
            <div className="notice">
              <HotelStatusBadge status={hotel.status} /> &nbsp;
              {hotel.status === 'PENDING_VERIFICATION' && 'Our team is verifying your documents. You will start receiving bid requests once you are live.'}
              {hotel.status === 'REJECTED' && `Not approved: ${hotel.rejectionReason ?? 'please check your documents'}. Fix it under Profile & documents and resubmit.`}
              {hotel.status === 'SUSPENDED' && 'This hotel is suspended and cannot receive bids. Please contact BidStay support.'}
            </div>
          )}
          <Outlet />
        </main>
      </div>
    </HotelContext.Provider>
  );
}

// A new owner with no hotel yet only sees the registration form.
import Register from './Register';
function RegisterOnly() {
  return <div className="main"><Register first /></div>;
}
