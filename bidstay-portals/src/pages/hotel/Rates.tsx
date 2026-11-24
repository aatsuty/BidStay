import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../lib/api';
import { inr } from '../../lib/format';
import type { Rates as RatesT } from '../../lib/types';
import { ErrorBox, Loading, PageHead } from '../../components/ui';
import { useHotel } from './HotelContext';
import HourlyRates from './HourlyRates';

export default function Rates() {
  const { hotel } = useHotel();
  const api = useApi();
  const q = useQuery({ queryKey: ['rates', hotel.id], queryFn: () => api.get<RatesT>(`/api/v1/partner/hotels/${hotel.id}/rates`) });
  return (
    <>
      <PageHead title="Rates & rules" sub="Your minimum price is private. Guests never see it." />
      <ErrorBox error={q.error} />
      {q.isLoading || !q.data ? <Loading /> : <RatesForm key={`nightly-${hotel.id}`} initial={q.data} />}
      <HourlyRates key={`hourly-${hotel.id}`} hotelId={hotel.id} />
    </>
  );
}

const roundUp50 = (n: number) => Math.ceil(n / 50) * 50;

function RatesForm({ initial }: { initial: RatesT }) {
  const { hotel } = useHotel();
  const api = useApi();
  const qc = useQueryClient();
  const [f, setF] = useState(initial);
  const [test, setTest] = useState('1100');
  const [saved, setSaved] = useState(false);

  const save = useMutation({
    mutationFn: () => api.put<RatesT>(`/api/v1/partner/hotels/${hotel.id}/rates`, {
      autoMode: f.autoMode, autoCounter: f.autoCounter, autoCounterPct: f.autoCounterPct, autoDecline: f.autoDecline,
      rooms: f.rooms.map((r) => ({ roomTypeId: r.roomTypeId, listedPrice: r.listedPrice, minPrice: r.minPrice })),
    }),
    onSuccess: (data) => { setF(data); setSaved(true); setTimeout(() => setSaved(false), 2500); qc.invalidateQueries({ queryKey: ['rates', hotel.id] }); },
  });

  const setRoom = (id: number, patch: Partial<RatesT['rooms'][number]>) => { setSaved(false); setF({ ...f, rooms: f.rooms.map((r) => (r.roomTypeId === id ? { ...r, ...patch } : r)) }); };
  const set = (patch: Partial<RatesT>) => { setSaved(false); setF({ ...f, ...patch }); };
  const invalid = f.rooms.some((r) => !(r.listedPrice > 0) || !(r.minPrice > 0) || r.minPrice > r.listedPrice);

  /** Same rules the server applies (BidService.evaluate), so the owner can see what a bid would do. */
  function outcome(price: number, minPrice: number, listed: number): [string, string] {
    if (!f.autoMode) return ['Waits for your reply (manual mode)', 'warn'];
    if (price >= minPrice) return ['Accepted automatically', 'ok'];
    if (f.autoCounter && price >= minPrice * (1 - f.autoCounterPct / 100)) return [`Countered automatically at ${inr(Math.min(roundUp50(minPrice), listed))}`, 'info'];
    if (f.autoDecline) return ['Declined automatically', 'bad'];
    return ['Waits for your reply', 'warn'];
  }

  return (
    <div className="stack">
      <div className="card">
        <h2>How should BidStay answer guest bids?</h2>
        <label className="toggle"><input type="checkbox" checked={f.autoMode} onChange={(e) => set({ autoMode: e.target.checked, ...(e.target.checked ? {} : { autoCounter: false, autoDecline: false }) })} />
          <span><b>Automatic mode</b><div className="muted small">Accept any bid at or above your minimum price, so you don't have to watch the phone. Turn it off to approve every bid yourself.</div></span></label>
        <label className="toggle" style={{ opacity: f.autoMode ? 1 : .45 }}><input type="checkbox" disabled={!f.autoMode} checked={f.autoCounter} onChange={(e) => set({ autoCounter: e.target.checked })} />
          <span><b>Also counter bids just below my minimum</b>
            <div className="muted small">Within <select className="input" style={{ width: 90, display: 'inline-block', padding: '3px 8px' }} disabled={!f.autoMode} value={f.autoCounterPct} onChange={(e) => set({ autoCounterPct: Number(e.target.value) })}>
              {[5, 10, 15, 20].map((p) => <option key={p} value={p}>{p}%</option>)}</select> below the minimum, BidStay offers your minimum price (rounded up to the next ₹50).</div></span></label>
        <label className="toggle" style={{ opacity: f.autoMode ? 1 : .45 }}><input type="checkbox" disabled={!f.autoMode} checked={f.autoDecline} onChange={(e) => set({ autoDecline: e.target.checked })} />
          <span><b>Decline everything lower automatically</b><div className="muted small">Otherwise lower bids wait for your reply.</div></span></label>
      </div>

      <div className="card">
        <h2>Room prices</h2>
        <div className="table-wrap"><table>
          <thead><tr><th>Room</th><th>Listed price</th><th>Minimum price (private)</th><th>Rooms</th><th>If a guest bids…</th></tr></thead>
          <tbody>{f.rooms.map((r) => {
            const [text, tone] = outcome(Number(test), r.minPrice, r.listedPrice);
            return (
              <tr key={r.roomTypeId}>
                <td><b>{r.name}</b><div className="muted small">{r.category}</div></td>
                <td><input className="input" style={{ width: 120 }} inputMode="numeric" value={r.listedPrice || ''} onChange={(e) => setRoom(r.roomTypeId, { listedPrice: Number(e.target.value.replace(/\D/g, '')) })} /></td>
                <td><input className="input" style={{ width: 120 }} inputMode="numeric" value={r.minPrice || ''} onChange={(e) => setRoom(r.roomTypeId, { minPrice: Number(e.target.value.replace(/\D/g, '')) })} />
                  {r.minPrice > r.listedPrice && <div className="small" style={{ color: 'var(--bad)' }}>Cannot be above listed price</div>}</td>
                <td>{r.totalRooms}</td>
                <td><span className={'badge ' + tone}>{text}</span></td>
              </tr>);
          })}</tbody>
        </table></div>
        <div className="row" style={{ marginTop: 12 }}>
          <label className="field" style={{ width: 160 }}>Try a guest bid of ₹<input className="input" inputMode="numeric" value={test} onChange={(e) => setTest(e.target.value.replace(/\D/g, ''))} /></label>
        </div>
      </div>

      <ErrorBox error={save.error} />
      {saved && <div className="success">Saved. New bids use these rules right away.</div>}
      <button className="btn" disabled={invalid || save.isPending} onClick={() => save.mutate()}>{save.isPending ? 'Saving…' : 'Save changes'}</button>
    </div>
  );
}
