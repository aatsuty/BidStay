import { useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, ArrowRight, BedDouble, Clock3, MapPin } from 'lucide-react';
import { ErrorBox } from '../../components/ui';
import { useApi } from '../../lib/api';
import { fmtDate, todayISO } from '../../lib/format';
import { useSession } from '../../lib/session';
import { useGuest } from './GuestShell';
import HotelDetails from './HotelDetails';
import { addDays, mediaUrl, rupees, type Booking } from './models';

type Criteria = { city: string; date: string; arrival: string; hours: number; category: string; rooms: number; guests: number };
type Listing = { hotelId: number; hotelName: string; area: string; roomTypeId: number; roomName: string; ratePerRoom: number; photo?: string };

export default function HourlySearch() {
  const [criteria, setCriteria] = useState<Criteria>();
  const [hours, setHours] = useState(3);
  const [details, setDetails] = useState<Listing>();
  const attempt = useRef<{ fingerprint: string; id: string } | null>(null);
  const api = useApi();
  const { baseUrl, cacheKey } = useSession();
  const { signedIn, checkingSignIn, openSignIn } = useGuest();
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const results = useInfiniteQuery({
    queryKey: ['hourly-hotels', baseUrl, criteria], enabled: !!criteria, initialPageParam: 0,
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams(Object.entries({ ...criteria, after: pageParam })
        .map(([key, value]) => [key, String(value)]));
      return api.get<{ hotels: Listing[]; nextCursor?: number }>('/api/v1/hotels/hourly?' + params);
    },
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const checkout = useMutation({
    mutationFn: (listing: Listing) => {
      if (!criteria) throw new Error('Choose a stay first');
      const { date, arrival, hours, rooms, guests } = criteria;
      const body = { date, arrival, hours, rooms, guests, roomTypeId: listing.roomTypeId, ratePerRoom: listing.ratePerRoom };
      const fingerprint = JSON.stringify([cacheKey, body]);
      if (attempt.current?.fingerprint !== fingerprint) attempt.current = { fingerprint, id: crypto.randomUUID() };
      return api.post<Booking>('/api/v1/bookings/hourly', { ...body, requestId: attempt.current.id });
    },
    onSuccess: (booking) => {
      void queryClient.invalidateQueries({ queryKey: ['guest-direct-trips', cacheKey] });
      navigate('/checkout/' + booking.bookingId);
    },
  });

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    const next = { city: String(values.get('city')).trim(), date: String(values.get('date')),
      arrival: String(values.get('arrival')), hours, category: String(values.get('category')),
      rooms: Number(values.get('rooms')), guests: Number(values.get('guests')) };
    checkout.reset();
    if (JSON.stringify(next) === JSON.stringify(criteria)) void results.refetch();
    setCriteria(next);
  }

  const hotels = results.data?.pages.flatMap((page) => page.hotels) ?? [];
  return <main className="guest-container guest-trip">
    <Link to="/" className="guest-back"><ArrowLeft size={16} /> Overnight stays</Link>
    <h1>Hourly stays</h1>
    <p className="guest-trip-description">Book a room for 3 or 6 hours at participating hotels.</p>
    <form className="guest-checkout guest-hourly-search" onSubmit={search}>
      <label className="field">Destination<input className="input" name="city" defaultValue="Delhi" required maxLength={80} /></label>
      <label className="field">Date<input className="input" name="date" type="date" defaultValue={addDays(todayISO(), 1)}
        min={todayISO()} max={addDays(todayISO(), 30)} required /></label>
      <label className="field">Duration<select className="input" aria-label="Duration" value={hours} onChange={(event) => setHours(Number(event.target.value))}>
        <option value={3}>3 hours</option><option value={6}>6 hours</option>
      </select></label>
      <label className="field">Arrival (IST)<input className="input" name="arrival" type="time" min="08:00"
        max={hours === 3 ? '19:00' : '16:00'} step={1800} defaultValue="12:00" required /></label>
      <label className="field">Room type<select className="input" name="category">
        <option value="STANDARD">Standard</option><option value="DELUXE">Deluxe</option><option value="SUITE">Suite</option>
      </select></label>
      <label className="field">Rooms<input className="input" name="rooms" type="number" defaultValue={1} min={1} max={10} required /></label>
      <label className="field">Guests<input className="input" name="guests" type="number" defaultValue={2} min={1} max={40} required /></label>
      <button className="guest-cta" disabled={checkout.isPending}>Find hourly stays <ArrowRight size={16} /></button>
    </form>
    <ErrorBox error={results.error ?? checkout.error} />
    {criteria && <>
      <p className="guest-trip-description guest-withdraw"><Clock3 size={14} /> {fmtDate(criteria.date)} · {criteria.arrival} IST · {criteria.hours} hours</p>
      {results.isFetching && !results.data && <p role="status">Checking available rooms…</p>}
      {results.isSuccess && hotels.length === 0 && <div className="guest-empty"><BedDouble size={23} />
        <h3>No hotels available for this slot</h3><p>Try another time, date, or room type.</p></div>}
      <div className="guest-trip-list">{hotels.map((hotel) => <article className="guest-offer-row" key={hotel.roomTypeId}>
        {hotel.photo ? <img className="guest-hourly-photo" src={mediaUrl(baseUrl, hotel.photo)} alt="" loading="lazy" />
          : <span className="guest-hotel-icon"><BedDouble size={20} /></span>}
        <div><h2>{hotel.hotelName}</h2><p><MapPin size={14} /> {hotel.area} · {hotel.roomName}</p></div>
        <div className="guest-offer-amount"><strong>{rupees.format(hotel.ratePerRoom * criteria.rooms)}</strong><small>Full stay total</small></div>
        <div className="guest-offer-actions">
          <button type="button" className="guest-quiet" onClick={() => setDetails(hotel)}>Photos and rooms</button>
          <button type="button" className="guest-cta" disabled={checkingSignIn || checkout.isPending}
            onClick={() => signedIn ? checkout.mutate(hotel) : openSignIn()}>Book {criteria.hours} hours <ArrowRight size={16} /></button>
        </div>
      </article>)}</div>
      {results.hasNextPage && <button type="button" className="guest-quiet" disabled={results.isFetchingNextPage}
        onClick={() => { void results.fetchNextPage(); }}>Load more hotels</button>}
    </>}
    {details && <HotelDetails id={details.hotelId} name={details.hotelName} close={() => setDetails(undefined)} />}
  </main>;
}
