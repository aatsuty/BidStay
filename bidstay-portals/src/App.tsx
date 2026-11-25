import { useEffect } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useApi } from './lib/api';
import { useSession } from './lib/session';
import type { Me, Role } from './lib/types';
import Login from './pages/Login';
import HotelShell from './pages/hotel/HotelShell';
import Requests from './pages/hotel/Requests';
import Rates from './pages/hotel/Rates';
import Availability from './pages/hotel/Availability';
import Bookings from './pages/hotel/Bookings';
import Insights from './pages/hotel/Insights';
import Profile from './pages/hotel/Profile';
import Register from './pages/hotel/Register';
import AdminShell from './pages/admin/AdminShell';
import Overview from './pages/admin/Overview';
import AuditLog from './pages/admin/AuditLog';
import AdminHotels from './pages/admin/AdminHotels';
import AdminBookings from './pages/admin/AdminBookings';
import AdminBids from './pages/admin/AdminBids';
import { Loading } from './components/ui';
import ListingPhotos from './pages/hotel/ListingPhotos';
import ListingMediaReview from './pages/admin/ListingMediaReview';
import GuestShell from './pages/guest/GuestShell';
import Search from './pages/guest/Search';
import Alerts from './pages/guest/Alerts';
import HourlySearch from './pages/guest/HourlySearch';
import Reviews from './pages/admin/Reviews';
import Market from './pages/admin/Market';
import { Checkout, Offers, Trips } from './pages/guest/TripFlow';
import BookingTicket from './pages/guest/BookingTicket';

export function useMe() {
  const api = useApi();
  const { userId, cacheKey } = useSession();
  return useQuery({ queryKey: ['me', cacheKey], queryFn: () => api.get<Me>('/api/v1/me'), enabled: userId != null, retry: false });
}

const home = (role: Role) => (role === 'ADMIN' ? '/admin' : role === 'HOTEL_STAFF' ? '/hotel' : '/');

function Guard({ role, children }: { role: Role; children: React.ReactNode }) {
  const { userId } = useSession();
  const me = useMe();
  const { pathname, search } = useLocation();
  const bookingLink = role === 'HOTEL_STAFF' && pathname === '/hotel/bookings' && /^\?hotel=\d+&booking=\d+$/.test(search)
    ? pathname + search : undefined;
  const login = <Navigate to="/login" replace state={bookingLink ? { bookingLink } : undefined} />;
  if (userId == null) return login;
  if (me.isLoading) return <div className="main"><Loading /></div>;
  if (me.error || !me.data) return login;
  if (me.data.role !== role) return bookingLink ? login : <Navigate to={home(me.data.role)} replace />;
  return <>{children}</>;
}

export default function App() {
  const location = useLocation();
  useEffect(() => {
    window.ReactNativeWebView?.postMessage(JSON.stringify({ action: 'history', canGoBack: window.history.state?.idx > 0 }));
  }, [location]);
  return (
    <Routes>
      <Route element={<GuestShell />}>
        <Route index element={<Search />} />
        <Route path="/hourly" element={<HourlySearch />} />
        <Route path="/offers/:bidId" element={<Offers />} />
        <Route path="/checkout/:bookingId" element={<Checkout />} />
        <Route path="/ticket/:bookingId" element={<BookingTicket />} />
        <Route path="/trips" element={<Trips />} />
        <Route path="/alerts" element={<Alerts />} />
      </Route>
      <Route path="/login" element={<Login />} />
      <Route path="/hotel" element={<Guard role="HOTEL_STAFF"><HotelShell /></Guard>}>
        <Route index element={<Navigate to="requests" replace />} />
        <Route path="requests" element={<Requests />} />
        <Route path="rates" element={<Rates />} />
        <Route path="availability" element={<Availability />} />
        <Route path="bookings" element={<Bookings />} />
        <Route path="insights" element={<Insights />} />
        <Route path="profile" element={<Profile />} />
        <Route path="photos" element={<ListingPhotos />} />
        <Route path="register" element={<Register />} />
      </Route>
      <Route path="/admin" element={<Guard role="ADMIN"><AdminShell /></Guard>}>
        <Route index element={<Overview />} />
        <Route path="hotels" element={<AdminHotels />} />
        <Route path="audit" element={<AuditLog />} />
        <Route path="photos" element={<ListingMediaReview />} />
        <Route path="bookings" element={<AdminBookings />} />
        <Route path="bids" element={<AdminBids />} />
        <Route path="reviews" element={<Reviews />} />
        <Route path="market" element={<Market />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
