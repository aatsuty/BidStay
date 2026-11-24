import { lazy, Suspense, useMemo, useState, type FormEvent } from 'react';
import { useInfiniteQuery, useMutation } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { ArrowDown, ArrowRight, BedDouble, CalendarDays, Check, ChevronDown, IndianRupee, List, Map, MapPin, Minus, Plus, Search as SearchIcon, ShieldCheck, Star, Users } from 'lucide-react';
import { ErrorBox } from '../../components/ui';
import { useApi } from '../../lib/api';
import { todayISO } from '../../lib/format';
import { useSession } from '../../lib/session';
import { useGuest } from './GuestShell';
import { addDays, rupees, type Bid, type HotelPage, type HotelResult } from './models';

const MAX_SELECTED = 50;
const HotelMap = lazy(() => import('./HotelMap'));
const ROOM_OPTIONS = ['STANDARD', 'DELUXE', 'SUITE'] as const;
type SearchFields = { city: string; checkIn: string; checkOut: string; rooms: number; guests: number; category: typeof ROOM_OPTIONS[number] };

function initialSearch(): SearchFields {
  const checkIn = addDays(todayISO(), 1);
  return { city: 'Delhi', checkIn, checkOut: addDays(checkIn, 1), rooms: 1, guests: 2, category: 'STANDARD' };
}

export default function Search() {
  const navigate = useNavigate();
  const { signedIn, openSignIn } = useGuest();
  const { baseUrl } = useSession();
  const api = useApi();
  const [form, setForm] = useState(initialSearch);
  const [criteria, setCriteria] = useState(form);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [area, setArea] = useState('');
  const [panel, setPanel] = useState<'map' | 'list'>('map');
  const [price, setPrice] = useState('1500');

  const hotelQuery = useInfiniteQuery({
    queryKey: ['hotel-search', baseUrl, criteria],
    initialPageParam: 0,
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({
        ...criteria, rooms: String(criteria.rooms), guests: String(criteria.guests), after: String(pageParam),
      });
      return api.get<HotelPage>('/api/v1/hotels/search?' + params);
    },
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const hotels = useMemo(() => hotelQuery.data?.pages.flatMap((page) => page.hotels) ?? [], [hotelQuery.data]);
  const areas = useMemo(() => [...new Set(hotels.map((hotel) => hotel.area))].sort(), [hotels]);
  const visible = useMemo(() => area ? hotels.filter((hotel) => hotel.area === area) : hotels, [hotels, area]);
  const targets = hotels.filter((hotel) => selected.has(hotel.id));
  const listed = targets.length ? targets : visible;
  const priceHint = listed.length ? listed.reduce((total, hotel) => total + hotel.listedPrice, 0) / listed.length : 0;
  const offer = Number(price);
  const nights = Math.round((Date.parse(criteria.checkOut) - Date.parse(criteria.checkIn)) / 86_400_000);

  const send = useMutation({
    mutationFn: () => api.post<Bid>('/api/v1/bids', { ...criteria, pricePerNight: offer, hotelIds: targets.map((hotel) => hotel.id) }),
    onSuccess: (bid) => navigate('/offers/' + bid.bidId),
  });

  function change<K extends keyof SearchFields>(key: K, value: SearchFields[K]) {
    setForm((previous) => ({ ...previous, [key]: value }));
  }

  function runSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSelected(new Set());
    setArea('');
    setCriteria({ ...form, city: form.city.trim() });
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

  return (
    <main className="guest-container guest-search">
      <section className="guest-hero" aria-labelledby="search-title">
        <div className="guest-hero-content">
          <span className="guest-location"><MapPin size={15} /> Delhi NCR</span>
          <h1 id="search-title">Your stay.<br /><em>Your price.</em></h1>
          <p>Send one bid to the hotels you choose. Compare replies and book the stay that works for you.</p>
          <a href="#find-hotels" className="guest-hero-link">Find a stay <ArrowDown size={15} /></a>
        </div>
      </section>
      <form onSubmit={runSearch} className="guest-search-form" id="find-hotels">
        <label><span><MapPin size={16} /> Destination</span><input required maxLength={80} value={form.city} onChange={(event) => change('city', event.target.value)} /></label>
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
      <section className="guest-results" aria-labelledby="results-title">
        <div className="guest-results-main">
          <div className="guest-section-head">
            <div><h2 id="results-title">Choose your hotels</h2><p>{hotelQuery.isPending ? 'Checking room availability…' : `${visible.length}${hotelQuery.hasNextPage && !area ? '+' : ''} available in ${criteria.city}`}</p></div>
            <div className="guest-switch" aria-label="Hotel view">
              <button aria-pressed={panel === 'map'} onClick={() => setPanel('map')}><Map size={16} /> Map</button>
              <button aria-pressed={panel === 'list'} onClick={() => setPanel('list')}><List size={16} /> List</button>
            </div>
          </div>
          <div className="guest-filters">
            <label><span className="guest-visually-hidden">Neighbourhood</span><select value={area} onChange={(event) => setArea(event.target.value)}><option value="">All neighbourhoods</option>{areas.map((option) => <option key={option}>{option}</option>)}</select><ChevronDown size={15} /></label>
            <label><span className="guest-visually-hidden">Room type</span><select value={form.category} onChange={(event) => {
              const category = event.target.value as SearchFields['category'];
              change('category', category);
              setCriteria((previous) => ({ ...previous, category }));
              setSelected(new Set());
            }}>{ROOM_OPTIONS.map((option) => <option key={option} value={option}>{option[0] + option.slice(1).toLowerCase()} room</option>)}</select><ChevronDown size={15} /></label>
            {visible.length > 0 && <button type="button" className="guest-quiet" onClick={() => setSelected((previous) => new Set([...previous, ...visible.map((hotel) => hotel.id)].slice(0, MAX_SELECTED)))}>Select visible</button>}
          </div>
          <ErrorBox error={hotelQuery.error} />
          {panel === 'map' && <Suspense fallback={<div className="guest-map" role="status">Loading map…</div>}><HotelMap hotels={visible} selected={selected} toggle={toggle} /></Suspense>}
          <div className={panel === 'map' ? 'guest-hotels beneath-map' : 'guest-hotels'}>
            {!hotelQuery.isPending && !hotelQuery.error && !visible.length && <div className="guest-empty"><BedDouble size={25} /><h3>No available hotels</h3><p>Try changing your dates, room type or guest count.</p></div>}
            {visible.map((hotel) => <HotelRow key={hotel.id} hotel={hotel} selected={selected.has(hotel.id)} toggle={() => toggle(hotel.id)} />)}
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
          <ErrorBox error={send.error} />
          {searchDirty && <p className="guest-price-hint">Find hotels again to apply your updated search.</p>}
          <button className="guest-cta guest-submit" disabled={searchDirty || !targets.length || offer < 1 || offer > 100_000 || send.isPending} onClick={() => signedIn ? send.mutate() : openSignIn()}>
            {send.isPending ? 'Sending your bid…' : !signedIn ? 'Sign in to bid' : targets.length ? `Send bid to ${targets.length} ${targets.length === 1 ? 'hotel' : 'hotels'}` : 'Choose hotels to bid'} <ArrowRight size={19} />
          </button>
          <p className="guest-price-note"><ShieldCheck size={17} /> You pay only if you choose an offer and complete checkout.</p>
        </aside>
      </section>
      <section className="guest-how" aria-label="How bidding works">
        <div><span><SearchIcon size={19} /></span><h3>Choose your stay</h3><p>Find hotels with space for your dates.</p></div>
        <div><span><IndianRupee size={19} /></span><h3>Send one bid</h3><p>Selected hotels can accept or counter your price.</p></div>
        <div><span><Check size={19} /></span><h3>Book your offer</h3><p>Pick the reply that suits you and check out.</p></div>
        <Link to="/trips">View your trips <ArrowRight size={16} /></Link>
      </section>
    </main>
  );
}

function HotelRow({ hotel, selected, toggle }: { hotel: HotelResult; selected: boolean; toggle: () => void }) {
  return (
    <button className={'guest-hotel' + (selected ? ' selected' : '')} type="button" aria-pressed={selected} onClick={toggle}>
      <span className="guest-hotel-icon"><BedDouble size={21} /></span>
      <span className="guest-hotel-description">
        <strong>{hotel.name}</strong>
        <span><MapPin size={13} /> {hotel.area}
          {hotel.starRating != null && hotel.starRating > 0 && <span><Star size={12} /> {hotel.starRating}-star</span>}
          {hotel.verified && <span><ShieldCheck size={13} /> Verified</span>}
        </span>
      </span>
      <span className="guest-hotel-rate"><strong>{rupees.format(hotel.listedPrice)}</strong><small>per night</small></span>
      <span className="guest-select-icon">{selected ? <Check size={16} /> : <Plus size={16} />}</span>
    </button>
  );
}
