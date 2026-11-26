import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Building2, CalendarDays, ChartNoAxesColumnIncreasing, ClipboardCheck, Files, Images, MessagesSquare, Plus, SlidersHorizontal } from 'lucide-react';
import { useApi } from '../../lib/api';
import type { HotelOffer, MyHotel } from '../../lib/types';
import { ErrorBox, HotelStatusBadge, Loading } from '../../components/ui';
import PortalLayout, { type PortalLink } from '../../components/PortalLayout';
import { useMe } from '../../App';
import { HotelContext } from './HotelContext';

const KEY = 'bidstay.portal.hotel';
const register: PortalLink = { to: '/hotel/register', label: 'Register another hotel', icon: Plus };

export default function HotelShell() {
  const api = useApi();
  const nav = useNavigate();
  const loc = useLocation();
  const me = useMe();
  const [picked, setPicked] = useState<number | null>(() => Number(localStorage.getItem(KEY)) || null);

  const hotelsQ = useQuery({ queryKey: ['partnerHotels'], queryFn: () => api.get<MyHotel[]>('/api/v1/partner/hotels') });
  const hotels = hotelsQ.data ?? [];
  const linkedHotel = loc.pathname === '/hotel/bookings' ? Number(new URLSearchParams(loc.search).get('hotel')) : 0;
  const hotel = hotels.find((h) => h.id === linkedHotel) ?? hotels.find((h) => h.id === picked) ?? hotels[0];

  const offersQ = useQuery({
    queryKey: ['offers', hotel?.id],
    queryFn: () => api.get<HotelOffer[]>(`/api/v1/partner/hotels/${hotel!.id}/offers`),
    enabled: !!hotel,
    refetchInterval: 5000,
  });

  const select = (id: number) => { localStorage.setItem(KEY, String(id)); setPicked(id); };
  if (hotelsQ.isLoading) return <div className="main"><Loading /></div>;
  if (hotelsQ.error) return <div className="main"><ErrorBox error={hotelsQ.error} /><button className="btn ghost" onClick={() => void hotelsQ.refetch()}>Try again</button></div>;
  if (!hotel && loc.pathname !== '/hotel/register' && loc.pathname !== '/hotel/account') {
    return <Navigate to="/hotel/register" replace />;
  }

  const requests: PortalLink = { to: '/hotel/requests', label: 'Requests', icon: MessagesSquare, count: offersQ.data?.length };
  const bookings: PortalLink = { to: '/hotel/bookings', label: 'Bookings', icon: ClipboardCheck };
  const availability: PortalLink = { to: '/hotel/availability', label: 'Availability', icon: CalendarDays };
  const rates: PortalLink = { to: '/hotel/rates', label: 'Rates & rules', icon: SlidersHorizontal };
  const insights: PortalLink = { to: '/hotel/insights', label: 'Insights', icon: ChartNoAxesColumnIncreasing };
  const profile: PortalLink = { to: '/hotel/profile', label: 'Profile & documents', icon: Files };
  const photos: PortalLink = { to: '/hotel/photos', label: 'Listing photos', icon: Images };
  const registration = hotel ? register : { ...register, label: 'Register your hotel', shortLabel: 'Register' };
  const links = hotel ? [requests, rates, availability, bookings, insights, profile, photos, registration] : [registration];
  const tabs = hotel ? [requests, bookings, availability] : [registration];
  const header = hotel && <label className="portal-property">
    <Building2 size={21} aria-hidden="true" />
    <span><span>Active hotel</span><select aria-label="Active hotel" value={hotel.id} onChange={(event) => {
      select(Number(event.target.value));
      nav('/hotel/requests');
    }}>
      {hotels.map((property) => <option key={property.id} value={property.id}>{property.name}</option>)}
    </select></span>
  </label>;

  return <HotelContext.Provider value={{ hotel, hotels, select }}>
    <PortalLayout role="Hotel partner" name={me.data?.name} links={links} tabs={tabs} accountPath="/hotel/account"
      settings={hotel ? [rates, photos, profile, insights, registration] : [registration]} header={header}>
      {hotel && hotel.status !== 'ACTIVE' && (
        <div className="notice">
          <HotelStatusBadge status={hotel.status} />{' '}
          {hotel.status === 'PENDING_VERIFICATION' && 'Our team is verifying your documents. You will start receiving bid requests once you are live.'}
          {hotel.status === 'REJECTED' && `Not approved: ${hotel.rejectionReason ?? 'please check your documents'}. Fix it under Profile & documents and resubmit.`}
          {hotel.status === 'SUSPENDED' && 'This hotel is suspended and cannot receive bids. Please contact BidStay support.'}
        </div>
      )}
    </PortalLayout>
  </HotelContext.Provider>;
}
