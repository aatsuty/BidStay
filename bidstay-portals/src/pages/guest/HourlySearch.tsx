import { lazy, Suspense, useMemo, useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, BedDouble, Clock3, LocateFixed, MapPin } from 'lucide-react';
import { ErrorBox } from '../../components/ui';
import { useApi } from '../../lib/api';
import { fmtDate, todayISO } from '../../lib/format';
import { useSession } from '../../lib/session';
import DestinationField from './DestinationField';
import { useGuest } from './GuestShell';
import HotelDetails from './HotelDetails';
import StayHeroImage from './StayHeroImage';
import StayTypeNav from './StayTypeNav';
import { addDays, destinationLabel, mediaUrl, rupees } from './models';
import { useDirectCheckout } from './useDirectCheckout';
import { useMapSearch } from './useMapSearch';

const HotelMap = lazy(() => import('./HotelMap'));
type Criteria = { city: string; area: string; date: string; arrival: string; hours: number; category: string; rooms: number; guests: number };
type Listing = { hotelId: number; hotelName: string; area: string; city?: string; roomTypeId: number; roomName: string;
  ratePerRoom: number; photo?: string; latitude?: number; longitude?: number };

function initialCount(value: string | null, max: number, fallback: number) {
  const count = Number(value);
  return Number.isInteger(count) && count >= 1 && count <= max ? count : fallback;
}

export default function HourlySearch() {
  const [params] = useSearchParams();
  const [destination, setDestination] = useState(() => ({
    city: params.get('city')?.trim().slice(0, 80) ?? '', area: params.get('area')?.trim().slice(0, 100) ?? '',
  }));
  const { city, area } = destination;
  const [criteria, setCriteria] = useState<Criteria>();
  const [mapOpen, setMapOpen] = useState(false);
  const [hours, setHours] = useState(() => params.get('hours') === '6' ? 6 : 3);
  const [details, setDetails] = useState<Listing>();
  const location = useMapSearch();
  const api = useApi();
  const startCheckout = useDirectCheckout();
  const { baseUrl, cacheKey } = useSession();
  const { signedIn, checkingSignIn, openSignIn } = useGuest();
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const results = useInfiniteQuery({
    queryKey: ['hourly-hotels', baseUrl, criteria, location.nearby, location.bounds], enabled: !!criteria, initialPageParam: 0,
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams(Object.entries({ ...criteria, ...location.nearby, ...location.bounds, after: pageParam })
        .map(([key, value]) => [key, String(value)]));
      return api.get<{ hotels: Listing[]; nextCursor?: number }>('/api/v1/hotels/hourly?' + params);
    },
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const checkout = useMutation({
    mutationFn: (listing: Listing) => {
      if (!criteria) throw new Error('Choose a stay first');
      const { date, arrival, hours, rooms, guests } = criteria;
      return startCheckout('hourly', { date, arrival, hours, rooms, guests,
        roomTypeId: listing.roomTypeId, ratePerRoom: listing.ratePerRoom });
    },
    onSuccess: (booking) => {
      void queryClient.invalidateQueries({ queryKey: ['guest-direct-trips', cacheKey] });
      navigate('/checkout/' + booking.bookingId);
    },
  });

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    const next = { city: city.trim(), area, date: String(values.get('date')),
      arrival: String(values.get('arrival')), hours, category: String(values.get('category')),
      rooms: Number(values.get('rooms')), guests: Number(values.get('guests')) };
    if (!next.city) return;
    setDestination({ city: next.city, area });
    if (next.city.toLowerCase() !== criteria?.city.toLowerCase() || next.area !== criteria?.area) location.reset();
    checkout.reset();
    if (JSON.stringify(next) === JSON.stringify(criteria)) void results.refetch();
    setCriteria(next);
  }

  const hotels = useMemo(() => results.data?.pages.flatMap((page) => page.hotels) ?? [], [results.data]);
  const mapHotels = useMemo(() => hotels.filter((hotel) => hotel.latitude != null && hotel.longitude != null)
    .map((hotel) => ({ id: hotel.hotelId, name: hotel.hotelName, latitude: hotel.latitude,
      longitude: hotel.longitude, listedPrice: hotel.ratePerRoom })), [hotels]);
  const today = todayISO();
  const latest = addDays(today, 30);
  const date = params.get('date') ?? '';
  const validDate = /^\d{4}-\d{2}-\d{2}$/.test(date) && date >= today && date <= latest
    && Number.isFinite(Date.parse(date)) && addDays(date, 0) === date;
  const category = ['STANDARD', 'DELUXE', 'SUITE'].find((value) => value === params.get('category')) ?? 'STANDARD';
  const overnight = criteria
    ? { city, area, checkIn: criteria.date, category: criteria.category, rooms: String(criteria.rooms), guests: String(criteria.guests) }
    : { city, area, checkIn: date, category, rooms: params.get('rooms') ?? '', guests: params.get('guests') ?? '' };
  return <main className="guest-container guest-search guest-hourly">
    <section className="guest-hero guest-hero-hourly" aria-labelledby="hourly-title">
      <StayHeroImage />
      <div className="guest-hero-content">
        <h1 id="hourly-title">Hourly stays</h1>
        <p>Choose your arrival time and book a 3- or 6-hour stay.</p>
      </div>
    </section>
    <form className="guest-checkout guest-hourly-search" onSubmit={search}>
      <StayTypeNav overnightUrl={'/?' + new URLSearchParams(overnight)} />
      <DestinationField city={destinationLabel(city, area)} onChange={(city) => setDestination({ city, area: '' })}
        onChoose={({ city, area }) => setDestination({ city, area })} hourly />
      <label className="field">Date<input className="input" name="date" type="date"
        defaultValue={validDate ? date : addDays(today, 1)}
        min={today} max={latest} required /></label>
      <label className="field">Duration<select className="input" aria-label="Duration" value={hours} onChange={(event) => setHours(Number(event.target.value))}>
        <option value={3}>3 hours</option><option value={6}>6 hours</option>
      </select></label>
      <label className="field">Arrival (IST)<input className="input" name="arrival" type="time" min="08:00"
        max={hours === 3 ? '19:00' : '16:00'} step={1800} defaultValue="12:00" required /></label>
      <label className="field">Room type<select className="input" name="category" defaultValue={category}>
        <option value="STANDARD">Standard</option><option value="DELUXE">Deluxe</option><option value="SUITE">Suite</option>
      </select></label>
      <div className="guest-hourly-party">
        <label className="field">Rooms<input className="input" name="rooms" type="number" defaultValue={initialCount(params.get('rooms'), 10, 1)} min={1} max={10} required /></label>
        <label className="field">Guests<input className="input" name="guests" type="number" defaultValue={initialCount(params.get('guests'), 40, 2)} min={1} max={40} required /></label>
      </div>
      <button className="guest-cta" disabled={checkout.isPending}>Find hourly stays <ArrowRight size={16} /></button>
    </form>
    <ErrorBox error={results.error ?? checkout.error} />
    {criteria && criteria.city === city.trim() && criteria.area === area && <>
      <p className="guest-trip-description guest-withdraw"><Clock3 size={14} /> {destinationLabel(criteria.city, area)} · {fmtDate(criteria.date)} · {criteria.arrival} IST · {criteria.hours} hours</p>
      <div className="guest-filters">
        <button type="button" className="guest-quiet" aria-pressed={!!location.nearby} disabled={location.locating} onClick={location.toggleNearby}>
          <LocateFixed size={15} /> {location.locating ? 'Finding location…' : location.nearby ? 'Within 5 km' : 'Near me'}
        </button>
        {(mapHotels.length > 0 || location.bounds) && <button type="button" className="guest-quiet" aria-pressed={mapOpen}
          onClick={() => setMapOpen(!mapOpen)}>{mapOpen ? 'Hide map' : 'Show map'}</button>}
        {location.bounds && <button type="button" className="guest-quiet" onClick={() => location.changeBounds()}>Clear map area</button>}
      </div>
      {location.error && <p role="status" className="guest-price-hint">{location.error}</p>}
      {mapOpen && (mapHotels.length > 0 || location.bounds) && <Suspense fallback={<div className="guest-map" role="status">Loading map…</div>}>
        <HotelMap hotels={mapHotels} toggle={(id) => setDetails(hotels.find((hotel) => hotel.hotelId === id))}
          appliedBounds={location.bounds} onSearchArea={location.changeBounds} />
      </Suspense>}
      {results.isFetching && !results.data && <p role="status">Checking available rooms…</p>}
      {results.isSuccess && hotels.length === 0 && <div className="guest-empty"><BedDouble size={23} />
        <h3>No hotels available for this slot</h3><p>Try another time, date, or room type.</p></div>}
      <div className="guest-trip-list">{hotels.map((hotel) => <article className="guest-offer-row guest-hourly-result" key={hotel.roomTypeId}>
        {hotel.photo ? <img className="guest-hourly-photo" src={mediaUrl(baseUrl, hotel.photo)} alt="" loading="lazy" />
          : <span className="guest-hourly-photo guest-hourly-placeholder"><BedDouble size={26} /></span>}
        <div><h2>{hotel.hotelName}</h2><p><MapPin size={14} /> {hotel.city ? destinationLabel(hotel.city, hotel.area) : hotel.area} · {hotel.roomName}</p></div>
        <div className="guest-offer-amount"><strong>{rupees.format(hotel.ratePerRoom * criteria.rooms)}</strong>
          <small>{criteria.rooms === 1 ? `1 room · ${criteria.hours} hours` : `${rupees.format(hotel.ratePerRoom)} per room · ${criteria.hours} hours`}</small>
        </div>
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
