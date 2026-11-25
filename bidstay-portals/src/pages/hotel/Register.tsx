import { useContext, useId, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../lib/api';
import { useSession } from '../../lib/session';
import type { MyHotel, RegisterHotel, RoomCategory, RoomInput } from '../../lib/types';
import { ErrorBox, PageHead } from '../../components/ui';
import { HotelContext } from './HotelContext';
import LocationPin from './LocationPin';
import { emptyPin, parsePin } from './map-pin';

const blank = (category: RoomCategory = 'STANDARD'): RoomInput => ({ category, name: category === 'STANDARD' ? 'Standard Room' : category === 'DELUXE' ? 'Deluxe Room' : 'Suite', maxGuests: 2, listedPrice: 0, minPrice: 0, totalRooms: 5 });

export default function Register({ first, onRegistered }: { first?: boolean; onRegistered?: (id: number) => void }) {
  const api = useApi();
  const qc = useQueryClient();
  const nav = useNavigate();
  const { cacheKey } = useSession();
  const hotelContext = useContext(HotelContext);
  const [f, setF] = useState<Omit<RegisterHotel, 'rooms'>>({ name: '', city: '', area: '', address: '', starRating: 2, gstNumber: '', contactPhone: '' });
  const [rooms, setRooms] = useState<RoomInput[]>([blank()]);
  const [coordinates, setCoordinates] = useState(emptyPin);
  const cityList = useId();
  const areaList = useId();
  const pin = parsePin(coordinates);
  const cities = useQuery({
    queryKey: ['partner-location-names', cacheKey, f.city],
    queryFn: () => api.get<string[]>('/api/v1/partner/location-names?' + new URLSearchParams({ prefix: f.city })),
    retry: false, staleTime: 60_000,
  });
  const areas = useQuery({
    queryKey: ['partner-location-names', cacheKey, f.city, f.area],
    queryFn: () => api.get<string[]>('/api/v1/partner/location-names?' + new URLSearchParams({ city: f.city, prefix: f.area })),
    enabled: !!f.city.trim(), retry: false, staleTime: 60_000,
  });

  const create = useMutation({
    mutationFn: () => api.post<MyHotel>('/api/v1/partner/hotels', { ...f, rooms, ...pin }),
    onSuccess: async (hotel) => {
      const select = onRegistered ?? hotelContext?.select;
      select?.(hotel.id);
      await qc.invalidateQueries({ queryKey: ['partnerHotels'] });
      nav('/hotel/profile');
    },
  });
  const upd = (patch: Partial<typeof f>) => setF({ ...f, ...patch });
  const updRoom = (i: number, patch: Partial<RoomInput>) => setRooms(rooms.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const num = (v: string) => Number(v.replace(/\D/g, ''));
  const roomsOk = rooms.every((r) => r.listedPrice > 0 && r.minPrice > 0 && r.minPrice <= r.listedPrice && r.totalRooms > 0);
  const ok = f.name.trim() && f.city.trim() && f.area.trim() && f.address.trim() && roomsOk && pin;
  const used = new Set(rooms.map((r) => r.category));
  const free = (['STANDARD', 'DELUXE', 'SUITE'] as RoomCategory[]).filter((c) => !used.has(c));

  return (
    <>
      <PageHead title={first ? 'Welcome! Register your hotel' : 'Register another hotel'} sub="After this, upload your four documents. We verify them, then you go live." />
      <ErrorBox error={create.error} />
      <div className="card stack">
        <div className="row wrap">
          <label className="field grow">Hotel name<input className="input" value={f.name} onChange={(e) => upd({ name: e.target.value })} /></label>
          <label className="field" style={{ width: 130 }}>Star rating<select className="input" value={f.starRating} onChange={(e) => upd({ starRating: Number(e.target.value) })}>{[0, 1, 2, 3, 4, 5].map((s) => <option key={s} value={s}>{s === 0 ? 'Unrated' : '★'.repeat(s)}</option>)}</select></label>
        </div>
        <div className="row wrap">
          <label className="field grow">City<input className="input" maxLength={80} list={cityList}
            value={f.city} onChange={(e) => upd({ city: e.target.value, area: '' })} /></label>
          <datalist id={cityList}>{cities.data?.map((name) => <option value={name} key={name} />)}</datalist>
          <label className="field grow">Neighbourhood<input className="input" maxLength={80} list={areaList}
            value={f.area} onChange={(e) => upd({ area: e.target.value })} /></label>
          <datalist id={areaList}>{areas.data?.map((name) => <option value={name} key={name} />)}</datalist>
        </div>
        <label className="field">Full address (shown to guests only after payment)<input className="input" value={f.address} onChange={(e) => upd({ address: e.target.value })} /></label>
        <div className="row wrap">
          <label className="field grow">GST number<input className="input" value={f.gstNumber} onChange={(e) => upd({ gstNumber: e.target.value })} /></label>
          <label className="field grow">WhatsApp number for bid requests<input className="input" value={f.contactPhone} onChange={(e) => upd({ contactPhone: e.target.value.replace(/\D/g, '') })} /></label>
        </div>
      </div>
      <div className="card">
        <h2>Hotel map pin <small className="muted">(optional)</small></h2>
        <LocationPin value={coordinates} onChange={setCoordinates} />
      </div>
      <div className="card">
        <h2>Rooms</h2>
        {rooms.map((r, i) => (
          <div className="row wrap" key={r.category} style={{ alignItems: 'flex-end', marginBottom: 10 }}>
            <label className="field" style={{ width: 120 }}>Type<input className="input" disabled value={r.category} /></label>
            <label className="field grow">Name<input className="input" value={r.name} onChange={(e) => updRoom(i, { name: e.target.value })} /></label>
            <label className="field" style={{ width: 100 }}>Listed ₹<input className="input" inputMode="numeric" value={r.listedPrice || ''} onChange={(e) => updRoom(i, { listedPrice: num(e.target.value) })} /></label>
            <label className="field" style={{ width: 110 }}>Minimum ₹<input className="input" inputMode="numeric" value={r.minPrice || ''} onChange={(e) => updRoom(i, { minPrice: num(e.target.value) })} /></label>
            <label className="field" style={{ width: 80 }}>Rooms<input className="input" inputMode="numeric" value={r.totalRooms || ''} onChange={(e) => updRoom(i, { totalRooms: num(e.target.value) })} /></label>
            <label className="field" style={{ width: 80 }}>Guests<input className="input" inputMode="numeric" value={r.maxGuests || ''} onChange={(e) => updRoom(i, { maxGuests: num(e.target.value) })} /></label>
            {rooms.length > 1 && <button className="btn danger sm" onClick={() => setRooms(rooms.filter((_, j) => j !== i))}>Remove</button>}
          </div>
        ))}
        {rooms.some((r) => r.minPrice > r.listedPrice) && <div className="small" style={{ color: 'var(--bad)' }}>A minimum price cannot be above the listed price.</div>}
        {free.length > 0 && <button className="btn ghost sm" onClick={() => setRooms([...rooms, blank(free[0])])}>+ Add {free[0].toLowerCase()} room</button>}
      </div>
      <button className="btn" disabled={!ok || create.isPending} onClick={() => create.mutate()}>{create.isPending ? 'Registering…' : 'Register hotel'}</button>
    </>
  );
}
