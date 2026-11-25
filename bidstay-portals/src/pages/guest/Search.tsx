import { lazy, Suspense, useMemo, useState, type FormEvent } from 'react';
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { ArrowDown, ArrowRight, BedDouble, Bell, CalendarDays, Check, ChevronDown, Clock3, IndianRupee, Images, List, LocateFixed, Map, MapPin, Minus, Plus, Search as SearchIcon, ShieldCheck, Star, Users } from 'lucide-react';
import { ErrorBox } from '../../components/ui';
import { useApi } from '../../lib/api';
import { todayISO } from '../../lib/format';
import { useSession } from '../../lib/session';
import DestinationField from './DestinationField';
import { useMapSearch } from './useMapSearch';
import HotelDetails from './HotelDetails';
import StayTypeNav from './StayTypeNav';
import { useGuest } from './GuestShell';
import { useDirectCheckout } from './useDirectCheckout';
import { addDays, destinationLabel, mediaUrl, rupees, type Bid, type HotelPage, type HotelResult } from './models';

const MAX_SELECTED = 50;
const HotelMap = lazy(() => import('./HotelMap'));
const ROOM_OPTIONS = ['STANDARD', 'DELUXE', 'SUITE'] as const;
type SearchFields = { city: string; area: string; checkIn: string; checkOut: string; rooms: number; guests: number; category: typeof ROOM_OPTIONS[number] };

function initialSearch(params: URLSearchParams): SearchFields {
  const today = todayISO();
  const validDate = (value: string | null): value is string => !!value
    && /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value)) && addDays(value, 0) === value;
  const inDate = params.get('checkIn');
  const checkIn = validDate(inDate) && inDate >= today ? inDate : addDays(today, 1);
  const outDate = params.get('checkOut');
  const checkOut = validDate(outDate) && outDate > checkIn && outDate <= addDays(checkIn, 30) ? outDate : addDays(checkIn, 1);
  const category = ROOM_OPTIONS.find((option) => option === params.get('category')) ?? 'STANDARD';
  const integer = (key: string, max: number, fallback: number) => {
    const value = Number(params.get(key));
    return Number.isInteger(value) && value >= 1 && value <= max ? value : fallback;
  };
  return { city: params.get('city')?.trim().slice(0, 80) ?? '', area: params.get('area')?.trim().slice(0, 100) ?? '', checkIn, checkOut,
    rooms: integer('rooms', 10, 1), guests: integer('guests', 40, 2), category };
}

export default function Search() {
  const navigate = useNavigate();
  const { signedIn, checkingSignIn, openSignIn } = useGuest();
  const { baseUrl, cacheKey } = useSession();
  const api = useApi();
  const checkout = useDirectCheckout();
  const queryClient = useQueryClient();
  const [params] = useSearchParams();
  const [form, setForm] = useState(() => initialSearch(params));
  const [criteria, setCriteria] = useState(form);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const { area } = criteria;
  const [minimumStars, setMinimumStars] = useState('');
  const [maximumRate, setMaximumRate] = useState('');
  const location = useMapSearch(() => setSelected(new Set()));
  const [panel, setPanel] = useState<'map' | 'list'>('map');
  const [price, setPrice] = useState(() => {
    const budget = Number(params.get('budget'));
    return budget >= 1 && budget <= 100_000 ? String(budget) : '1500';
  });
  const [details, setDetails] = useState<HotelResult>();

  const hotelQuery = useInfiniteQuery({
    queryKey: ['hotel-search', baseUrl, criteria, minimumStars, maximumRate, location.nearby, location.bounds],
    enabled: !!criteria.city,
    initialPageParam: 0,
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({
        ...criteria, rooms: String(criteria.rooms), guests: String(criteria.guests), after: String(pageParam),
        ...(minimumStars ? { minimumStars } : {}), ...(maximumRate ? { maximumRate } : {}), ...location.nearby, ...location.bounds,
      });
      return api.get<HotelPage>('/api/v1/hotels/search?' + params);
    },
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    refetchOnWindowFocus: 'always',
  });
  const hotels = useMemo(() => hotelQuery.data?.pages.flatMap((page) => page.hotels) ?? [], [hotelQuery.data]);
  const areas = hotelQuery.data?.pages[0]?.areas ?? [];
  const visible = hotels;
  const hasMapLocations = visible.some((hotel) => hotel.latitude != null && hotel.longitude != null);
  const canShowMap = hasMapLocations || !!location.bounds;
  const showMap = panel === 'map' && canShowMap;
  const selectableHotels = visible.slice(0, MAX_SELECTED);
  const allSelected = selectableHotels.length > 0 && selectableHotels.every((hotel) => selected.has(hotel.id));
  const targets = hotels.filter((hotel) => selected.has(hotel.id));
  const listed = targets.length ? targets : visible;
  const priceHint = listed.length ? listed.reduce((total, hotel) => total + hotel.listedPrice, 0) / listed.length : 0;
  const offer = Number(price);
  const nights = Math.round((Date.parse(criteria.checkOut) - Date.parse(criteria.checkIn)) / 86_400_000);

  const send = useMutation({
    mutationFn: () => api.post<Bid>('/api/v1/bids', { ...criteria, pricePerNight: offer, hotelIds: targets.map((hotel) => hotel.id) }),
    onSuccess: (bid) => navigate('/offers/' + bid.bidId),
  });

  const save = useMutation({
    mutationFn: () => api.post('/api/v1/guest/saved-searches', { ...criteria, budget: offer }),
  });

  const bookListed = useMutation({
    mutationFn: (hotel: HotelResult) => checkout('listed', {
      roomTypeId: hotel.roomTypeId, checkIn: criteria.checkIn, checkOut: criteria.checkOut,
      rooms: criteria.rooms, guests: criteria.guests, listedRate: hotel.listedPrice,
    }),
    onSuccess: (booking) => {
      void queryClient.invalidateQueries({ queryKey: ['guest-direct-trips', cacheKey] });
      navigate('/checkout/' + booking.bookingId);
    },
  });

  function change<K extends keyof SearchFields>(key: K, value: SearchFields[K]) {
    setForm((previous) => ({ ...previous, [key]: value }));
  }

  function runSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const next = { ...form, city: form.city.trim() };
    if (!next.city) return;
    setSelected(new Set());
    setForm(next);
    if (criteria.city.toLowerCase() !== next.city.toLowerCase() || criteria.area !== next.area) location.reset();
    if (JSON.stringify(next) === JSON.stringify(criteria)) void hotelQuery.refetch();
    setCriteria(next);
    send.reset();
  }

  function toggle(id: number) {
    setSelected((previous) => {
      const next = new Set(previous);
      if (next.has(id)) next.delete(id);
      else if (next.size < MAX_SELECTED) next.add(id);
      return next;
    });
  }

  function updatePrice(delta: number) {
    setPrice(String(Math.min(100_000, Math.max(1, (offer || 0) + delta))));
  }

  const searchDirty = Object.keys(criteria).some((key) => criteria[key as keyof SearchFields] !== form[key as keyof SearchFields]);
  const hourlyUrl = '/hourly?' + new URLSearchParams({ city: form.city, area: form.area, date: form.checkIn,
    category: form.category, guests: String(form.guests), rooms: String(form.rooms) });

  return (
    <main className="guest-container guest-search">
      <section className="guest-hero" aria-labelledby="search-title">
        <div className="guest-hero-content">
          <h1 id="search-title">Your stay.<br /><em>Your price.</em></h1>
          <p>Send one bid to the hotels you choose. Compare replies and book the stay that works for you.</p>
          <div className="guest-stay-links">
            <a href="#find-hotels" className="guest-hero-link">Find a stay <ArrowDown size={15} /></a>
            <Link to={hourlyUrl} className="guest-cta"><Clock3 size={17} /> Hourly stays</Link>
          </div>
        </div>
      </section>
      <form onSubmit={runSearch} className="guest-search-form" id="find-hotels">
        <StayTypeNav hourlyUrl={hourlyUrl} />
        <DestinationField city={destinationLabel(form.city, form.area)} onChange={(city) => setForm((previous) => ({ ...previous, city, area: '' }))}
          onChoose={({ city, area }) => setForm((previous) => ({ ...previous, city, area }))} />
        <label><span><CalendarDays size={16} /> Check-in</span><input type="date" required min={todayISO()} value={form.checkIn} onChange={(event) => {
          const checkIn = event.target.value;
          if (!checkIn) return;
          setForm((previous) => ({ ...previous, checkIn, checkOut: previous.checkOut > checkIn ? previous.checkOut : addDays(checkIn, 1) }));
        }} /></label>
        <label><span><CalendarDays size={16} /> Check-out</span><input type="date" required min={addDays(form.checkIn, 1)} max={addDays(form.checkIn, 30)} value={form.checkOut} onChange={(event) => change('checkOut', event.target.value)} /></label>
        <label><span><Users size={16} /> Guests</span><input type="number" required min={1} max={40} value={form.guests || ''} onChange={(event) => change('guests', Number(event.target.value))} /></label>
        <label><span><BedDouble size={16} /> Rooms</span><input type="number" required min={1} max={10} value={form.rooms || ''} onChange={(event) => change('rooms', Number(event.target.value))} /></label>
        <button className="guest-cta" type="submit"><SearchIcon size={18} /> Find hotels</button>
      </form>
      {criteria.city && <section className="guest-results" aria-labelledby="results-title">
        <div className="guest-results-main">
          <div className="guest-section-head">
            <div><h2 id="results-title">Choose your hotels</h2><p>{hotelQuery.isPending ? 'Checking room availability…' : `${visible.length}${hotelQuery.hasNextPage ? '+' : ''} available in ${destinationLabel(criteria.city, area)}`}</p></div>
            <div className="guest-result-actions">
              {visible.length > 1 && <button className="guest-quiet" type="button" onClick={() =>
                setSelected(allSelected ? new Set() : new Set(selectableHotels.map((hotel) => hotel.id)))}>
                {allSelected ? 'Clear selection' : visible.length > MAX_SELECTED ? `Select first ${MAX_SELECTED}` : 'Select all'}
              </button>}
              {canShowMap && <div className="guest-switch" aria-label="Hotel view">
                <button aria-pressed={panel === 'map'} onClick={() => setPanel('map')}><Map size={16} /> Map</button>
                <button aria-pressed={panel === 'list'} onClick={() => setPanel('list')}><List size={16} /> List</button>
              </div>}
            </div>
          </div>
          <div className="guest-filters">
            <label><span className="guest-visually-hidden">Neighbourhood</span><select value={areas.find((option) => option.toLowerCase() === area.toLowerCase()) ?? area} onChange={(event) => {
              const area = event.target.value;
              setForm((previous) => ({ ...previous, area }));
              setCriteria((previous) => ({ ...previous, area }));
              location.changeBounds();
            }}><option value="">All neighbourhoods</option>{area && !areas.some((option) => option.toLowerCase() === area.toLowerCase()) && <option value={area}>{area}</option>}{areas.map((option) => <option key={option}>{option}</option>)}</select><ChevronDown size={15} /></label>
            <label><span className="guest-visually-hidden">Room type</span><select value={form.category} onChange={(event) => {
              const category = event.target.value as SearchFields['category'];
              change('category', category);
              setCriteria((previous) => ({ ...previous, category }));
              setSelected(new Set());
            }}>{ROOM_OPTIONS.map((option) => <option key={option} value={option}>{option[0] + option.slice(1).toLowerCase()} room</option>)}</select><ChevronDown size={15} /></label>
            <label><span className="guest-visually-hidden">Minimum star rating</span><select value={minimumStars} onChange={(event) => {
              setMinimumStars(event.target.value); setSelected(new Set());
            }}><option value="">Any star rating</option>{[2, 3, 4, 5].map((stars) => <option key={stars} value={stars}>{stars} stars and up</option>)}</select><ChevronDown size={15} /></label>
            <label><span className="guest-visually-hidden">Maximum listed nightly rate</span><select value={maximumRate} onChange={(event) => {
              setMaximumRate(event.target.value); setSelected(new Set());
            }}><option value="">Any listed rate</option>{[1500, 2500, 5000, 10000].map((rate) => <option key={rate} value={rate}>Up to {rupees.format(rate)} / night</option>)}</select><ChevronDown size={15} /></label>
            <button type="button" className="guest-quiet" aria-pressed={!!location.nearby} disabled={location.locating} onClick={location.toggleNearby}>
              <LocateFixed size={15} /> {location.locating ? 'Finding location…' : location.nearby ? 'Within 5 km' : 'Near me'}
            </button>
            {location.bounds && <button type="button" className="guest-quiet" onClick={() => location.changeBounds()}>Clear map area</button>}
            {visible.length > 0 && <button type="button" className="guest-quiet" onClick={() => setSelected((previous) => new Set([...previous, ...visible.map((hotel) => hotel.id)].slice(0, MAX_SELECTED)))}>Select visible</button>}
          </div>
          {location.error && <p role="status" className="guest-price-hint">{location.error}</p>}
          <ErrorBox error={hotelQuery.error} />
          {showMap && <Suspense fallback={<div className="guest-map" role="status">Loading map…</div>}>
            <HotelMap hotels={visible} selected={selected} toggle={toggle} appliedBounds={location.bounds} onSearchArea={location.changeBounds} />
          </Suspense>}
          <div className={showMap ? 'guest-hotels beneath-map' : 'guest-hotels'}>
            {!hotelQuery.isPending && !hotelQuery.error && !visible.length && <div className="guest-empty"><BedDouble size={25} /><h3>No available hotels</h3><p>Try changing your dates, room type or guest count.</p></div>}
            {visible.map((hotel) => <HotelRow key={hotel.id} hotel={hotel} selected={selected.has(hotel.id)} toggle={() => toggle(hotel.id)} openDetails={() => setDetails(hotel)} baseUrl={baseUrl} />)}
          </div>
          {hotelQuery.hasNextPage && <button className="guest-quiet guest-more" disabled={hotelQuery.isFetchingNextPage} onClick={() => { void hotelQuery.fetchNextPage(); }}>Load more hotels</button>}
        </div>
        <aside className="guest-price" aria-labelledby="price-title">
          <div className="guest-price-icon"><IndianRupee size={22} /></div>
          <h2 id="price-title">Name your price</h2>
          <p>Set what you'd pay for one room, per night.</p>
          <div className="guest-price-entry">
            <button aria-label="Lower price by ₹100" onClick={() => updatePrice(-100)}><Minus size={17} /></button>
            <label><span>₹</span><input type="number" inputMode="decimal" aria-label="Bid in rupees per room per night" min={1} max={100_000} step="0.01" value={price} onChange={(event) => setPrice(event.target.value)} /></label>
            <button aria-label="Increase price by ₹100" onClick={() => updatePrice(100)}><Plus size={17} /></button>
          </div>
          {priceHint > 0 && <p className="guest-price-hint">Average listed price {rupees.format(priceHint)} per room</p>}
          <div className="guest-bid-summary">
            <span>{nights} {nights === 1 ? 'night' : 'nights'} · {criteria.rooms} {criteria.rooms === 1 ? 'room' : 'rooms'}</span>
            <strong>{rupees.format(Math.max(0, offer || 0) * nights * criteria.rooms)}</strong>
            <span>{targets.length} {targets.length === 1 ? 'hotel' : 'hotels'} selected</span>
            {!!targets.length && <button onClick={() => setSelected(new Set())}>Clear</button>}
          </div>
          <ErrorBox error={send.error ?? save.error} />
          {searchDirty && <p className="guest-price-hint">Find hotels again to apply your updated search.</p>}
          <button className="guest-cta guest-submit" disabled={checkingSignIn || searchDirty || !targets.length || offer < 1 || offer > 100_000 || send.isPending} onClick={() => signedIn ? send.mutate() : openSignIn()}>
            {send.isPending ? 'Sending your bid…' : !signedIn ? 'Sign in to bid' : targets.length ? `Send bid to ${targets.length} ${targets.length === 1 ? 'hotel' : 'hotels'}` : 'Choose hotels to bid'} <ArrowRight size={19} />
          </button>
          <button className="guest-save-search" type="button" disabled={checkingSignIn || searchDirty || offer < 1 || offer > 100_000 || save.isPending}
            onClick={() => signedIn ? save.mutate() : openSignIn()}><Bell size={16} /> {save.isPending ? 'Saving search…' : 'Save search and get alerts'}</button>
          {save.isSuccess && <p className="guest-save-success" role="status">Search saved. <Link to="/alerts">View alerts</Link></p>}
          <p className="guest-price-note"><ShieldCheck size={17} /> You pay only if you choose an offer and complete checkout.</p>
        </aside>
      </section>}
      {details && <HotelDetails id={details.id} name={details.name}
        selection={{ selected: selected.has(details.id), toggle: () => toggle(details.id) }}
        bookingAction={details.roomTypeId && <div>
          <ErrorBox error={bookListed.error} />
          <button type="button" className="guest-cta" disabled={checkingSignIn || searchDirty || bookListed.isPending}
            onClick={() => signedIn ? bookListed.mutate(details) : openSignIn()}>
            {bookListed.isPending ? 'Checking availability…' : `Book at listed price · ${rupees.format(details.listedPrice * nights * criteria.rooms)}`}
          </button>
        </div>}
        close={() => { setDetails(undefined); bookListed.reset(); }} />}
      <section className="guest-how" aria-label="How bidding works">
        <div><span><SearchIcon size={19} /></span><h3>Choose your stay</h3><p>Find hotels with space for your dates.</p></div>
        <div><span><IndianRupee size={19} /></span><h3>Send one bid</h3><p>Selected hotels can accept or counter your price.</p></div>
        <div><span><Check size={19} /></span><h3>Book your offer</h3><p>Pick the reply that suits you and check out.</p></div>
        <Link to="/trips">View your trips <ArrowRight size={16} /></Link>
      </section>
    </main>
  );
}

function HotelRow({ hotel, selected, toggle, openDetails, baseUrl }: {
  hotel: HotelResult; selected: boolean; toggle: () => void; openDetails: () => void; baseUrl: string;
}) {
  const cover = hotel.hotelPhoto ?? hotel.roomPhoto;
  return (
    <article className={'guest-hotel' + (selected ? ' selected' : '')}>
      <button type="button" className="guest-hotel-picture" onClick={openDetails} aria-label={`View photos of ${hotel.name}`}>
        {cover ? <img src={mediaUrl(baseUrl, cover)} alt="" loading="lazy" /> : <BedDouble size={23} />}
      </button>
      <div className="guest-hotel-description">
        <button className="guest-hotel-name" type="button" onClick={openDetails}>{hotel.name}</button>
        <span><MapPin size={13} /> {hotel.city ? destinationLabel(hotel.city, hotel.area) : hotel.area}
          {hotel.starRating != null && hotel.starRating > 0 && <span><Star size={12} /> {hotel.starRating}-star</span>}
          {hotel.verified && <span><ShieldCheck size={13} /> Verified</span>}
        </span>
        {hotel.roomPhoto && <button className="guest-room-link" type="button" onClick={openDetails}><Images size={13} /> See room photos</button>}
      </div>
      <span className="guest-hotel-rate"><strong>{rupees.format(hotel.listedPrice)}</strong><small>per night</small></span>
      <button className="guest-select-icon" type="button" aria-label={`${selected ? 'Remove' : 'Select'} ${hotel.name}`} aria-pressed={selected} onClick={toggle}>
        {selected ? <Check size={16} /> : <Plus size={16} />}
      </button>
    </article>
  );
}
