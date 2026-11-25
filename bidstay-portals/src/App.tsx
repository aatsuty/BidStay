import { lazy, Suspense, useEffect } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useApi } from './lib/api';
import { useSession } from './lib/session';
import type { Me, Role } from './lib/types';
import { Loading } from './components/ui';
import GuestShell from './pages/guest/GuestShell';
import Search from './pages/guest/Search';

import { PortalAccountPage } from './components/PortalLayout';

const Login = lazy(() => import('./pages/Login'));
const HotelShell = lazy(() => import('./pages/hotel/HotelShell'));
const Requests = lazy(() => import('./pages/hotel/Requests'));
const Rates = lazy(() => import('./pages/hotel/Rates'));
const Availability = lazy(() => import('./pages/hotel/Availability'));
const Bookings = lazy(() => import('./pages/hotel/Bookings'));
const Insights = lazy(() => import('./pages/hotel/Insights'));
const Profile = lazy(() => import('./pages/hotel/Profile'));
const Register = lazy(() => import('./pages/hotel/Register'));
const AdminShell = lazy(() => import('./pages/admin/AdminShell'));
const Overview = lazy(() => import('./pages/admin/Overview'));
const AuditLog = lazy(() => import('./pages/admin/AuditLog'));
const AdminHotels = lazy(() => import('./pages/admin/AdminHotels'));
const AdminBookings = lazy(() => import('./pages/admin/AdminBookings'));
const AdminBids = lazy(() => import('./pages/admin/AdminBids'));
const ListingPhotos = lazy(() => import('./pages/hotel/ListingPhotos'));
const ListingMediaReview = lazy(() => import('./pages/admin/ListingMediaReview'));
const Alerts = lazy(() => import('./pages/guest/Alerts'));
const HourlySearch = lazy(() => import('./pages/guest/HourlySearch'));
const Reviews = lazy(() => import('./pages/admin/Reviews'));
const Market = lazy(() => import('./pages/admin/Market'));
const BookingTicket = lazy(() => import('./pages/guest/BookingTicket'));
const Checkout = lazy(() => import('./pages/guest/TripFlow').then((pages) => ({ default: pages.Checkout })));
const Offers = lazy(() => import('./pages/guest/TripFlow').then((pages) => ({ default: pages.Offers })));
const Trips = lazy(() => import('./pages/guest/TripFlow').then((pages) => ({ default: pages.Trips })));

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
    <Suspense fallback={<main className="main"><Loading /></main>}>
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
          <Route path="account" element={<PortalAccountPage />} />
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
          <Route path="account" element={<PortalAccountPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Suspense>
  );
}
