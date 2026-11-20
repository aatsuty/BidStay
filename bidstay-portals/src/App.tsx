import { Navigate, Route, Routes } from 'react-router-dom';
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
import AdminHotels from './pages/admin/AdminHotels';
import AdminBookings from './pages/admin/AdminBookings';
import AdminBids from './pages/admin/AdminBids';
import { Loading } from './components/ui';

export function useMe() {
  const api = useApi();
  const { userId } = useSession();
  return useQuery({ queryKey: ['me', userId], queryFn: () => api.get<Me>('/api/v1/me'), enabled: userId != null, retry: false });
}

const home = (role: Role) => (role === 'ADMIN' ? '/admin' : role === 'HOTEL_STAFF' ? '/hotel' : '/login');

function Guard({ role, children }: { role: Role; children: React.ReactNode }) {
  const { userId, signOut } = useSession();
  const me = useMe();
  if (userId == null) return <Navigate to="/login" replace />;
  if (me.isLoading) return <div className="main"><Loading /></div>;
  if (me.error || !me.data) { signOut(); return <Navigate to="/login" replace />; }
  if (me.data.role !== role) return <Navigate to={home(me.data.role)} replace />;
  return <>{children}</>;
}

function Landing() {
  const { userId } = useSession();
  const me = useMe();
  if (userId == null) return <Navigate to="/login" replace />;
  if (!me.data) return <div className="main"><Loading /></div>;
  return <Navigate to={home(me.data.role)} replace />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/hotel" element={<Guard role="HOTEL_STAFF"><HotelShell /></Guard>}>
        <Route index element={<Navigate to="requests" replace />} />
        <Route path="requests" element={<Requests />} />
        <Route path="rates" element={<Rates />} />
        <Route path="availability" element={<Availability />} />
        <Route path="bookings" element={<Bookings />} />
        <Route path="insights" element={<Insights />} />
        <Route path="profile" element={<Profile />} />
        <Route path="register" element={<Register />} />
      </Route>
      <Route path="/admin" element={<Guard role="ADMIN"><AdminShell /></Guard>}>
        <Route index element={<Overview />} />
        <Route path="hotels" element={<AdminHotels />} />
        <Route path="bookings" element={<AdminBookings />} />
        <Route path="bids" element={<AdminBids />} />
      </Route>
      <Route path="*" element={<Landing />} />
    </Routes>
  );
}
