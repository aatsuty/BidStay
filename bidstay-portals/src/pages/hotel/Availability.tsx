import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../lib/api';
import { fmtDate, todayISO } from '../../lib/format';
import type { RoomAvailability } from '../../lib/types';
import { ErrorBox, Loading, PageHead } from '../../components/ui';
import { useHotel } from './HotelContext';

function plus(iso: string, n: number) {
  const [y, m, d] = iso.split('-').map(Number);
  const x = new Date(y, m - 1, d + n);
  return `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, '0')}-${String(x.getDate()).padStart(2, '0')}`;
}

export default function Availability() {
  const { hotel } = useHotel();
  const api = useApi();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['avail', hotel.id], queryFn: () => api.get<RoomAvailability[]>(`/api/v1/partner/hotels/${hotel.id}/availability?days=14`) });

  const [roomTypeId, setRoomTypeId] = useState<number | ''>('');
  const [from, setFrom] = useState(plus(todayISO(), 1));
  const [to, setTo] = useState(plus(todayISO(), 8));
  const [rooms, setRooms] = useState('5');
  const selected = roomTypeId || q.data?.[0]?.roomTypeId;

  const save = useMutation({
    mutationFn: () => api.put<RoomAvailability[]>(`/api/v1/partner/hotels/${hotel.id}/availability`, { roomTypeId: selected, from, to, totalRooms: Number(rooms) }),
    onSuccess: (d) => qc.setQueryData(['avail', hotel.id], d),
  });

  return (
    <>
      <PageHead title="Availability" sub="Rooms you can sell each night. Keep this accurate to avoid overbooking." />
      <ErrorBox error={q.error} />
      {q.isLoading && <Loading />}
      {q.data?.map((room) => (
        <div className="card" key={room.roomTypeId}>
          <h2>{room.name}</h2>
          <div className="grid-scroll"><table className="avail">
            <thead><tr>{room.nights.map((n) => <th key={n.date} className="muted small" style={{ fontWeight: 600 }}>{fmtDate(n.date)}</th>)}</tr></thead>
            <tbody><tr>{room.nights.map((n) => {
              const free = n.total - n.held - n.sold;
              return <td key={n.date}><div className={'cell' + (free === 0 ? ' full' : free <= 1 ? ' low' : '')}>{free} free<small>{n.sold} sold · {n.held} held</small><small>of {n.total}</small></div></td>;
            })}</tr></tbody>
          </table></div>
        </div>
      ))}
      <div className="card">
        <h2>Change how many rooms you sell</h2>
        <ErrorBox error={save.error} />
        <div className="row wrap" style={{ alignItems: 'flex-end' }}>
          <label className="field">Room type<select className="input" value={selected ?? ''} onChange={(e) => setRoomTypeId(Number(e.target.value))}>{q.data?.map((r) => <option key={r.roomTypeId} value={r.roomTypeId}>{r.name}</option>)}</select></label>
          <label className="field">From<input type="date" className="input" min={todayISO()} value={from} onChange={(e) => setFrom(e.target.value)} /></label>
          <label className="field">Until (not included)<input type="date" className="input" min={from} value={to} onChange={(e) => setTo(e.target.value)} /></label>
          <label className="field" style={{ width: 110 }}>Rooms per night<input className="input" inputMode="numeric" value={rooms} onChange={(e) => setRooms(e.target.value.replace(/\D/g, ''))} /></label>
          <button className="btn" disabled={!selected || !rooms || save.isPending} onClick={() => save.mutate()}>Update</button>
        </div>
        <p className="muted small">You cannot go below rooms already booked or on hold for a night.</p>
      </div>
    </>
  );
}
