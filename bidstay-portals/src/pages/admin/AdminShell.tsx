import { useQuery } from '@tanstack/react-query';
import { Building2, ChartNoAxesColumnIncreasing, ClipboardList, Gavel, History, Images, LayoutDashboard, Star } from 'lucide-react';
import { useApi } from '../../lib/api';
import type { AdminStats } from '../../lib/types';
import PortalLayout, { type PortalLink } from '../../components/PortalLayout';
import { useMe } from '../../App';

const overview: PortalLink = { to: '/admin', label: 'Overview', icon: LayoutDashboard, end: true };
const photos: PortalLink = { to: '/admin/photos', label: 'Listing photo review', icon: Images };
const bids: PortalLink = { to: '/admin/bids', label: 'Bids', icon: Gavel };
const bookings: PortalLink = { to: '/admin/bookings', label: 'Bookings & commission', icon: ClipboardList };
const reviews: PortalLink = { to: '/admin/reviews', label: 'Guest reviews', icon: Star };
const market: PortalLink = { to: '/admin/market', label: 'Neighbourhood pilot', icon: ChartNoAxesColumnIncreasing };
const audit: PortalLink = { to: '/admin/audit', label: 'Audit log', icon: History };

export default function AdminShell() {
  const api = useApi();
  const me = useMe();
  const stats = useQuery({ queryKey: ['adminStats'], queryFn: () => api.get<AdminStats>('/api/v1/admin/stats'), refetchInterval: 15000 });
  const hotels: PortalLink = { to: '/admin/hotels', label: 'Hotels', icon: Building2, count: stats.data?.pendingHotels };
  return <PortalLayout role="Admin" name={me.data?.name} accountPath="/admin/account"
    links={[overview, hotels, photos, bids, bookings, reviews, market, audit]}
    tabs={[overview, hotels, { ...bookings, shortLabel: 'Bookings' }]}
    settings={[photos, bids, reviews, market, audit]} />;
}
