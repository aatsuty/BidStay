import { useState } from 'react';
import { ListChecks, MessageCircle } from 'lucide-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { errMsg, useApi } from '../../lib/api';
import { countdown, fmtDate, fmtTime, inr, useNow } from '../../lib/format';
import type { ChatMessage, HotelAction, HotelOffer } from '../../lib/types';
import { ErrorBox, PageHead } from '../../components/ui';
import { useHotel } from './HotelContext';
import WhatsAppPreference from '../WhatsAppPreference';

export default function Requests() {
  const { hotel } = useHotel();
  const api = useApi();
  const now = useNow();
  const [section, setSection] = useState<'requests' | 'activity'>('requests');
  const offers = useQuery({
    queryKey: ['offers', hotel.id],
    queryFn: () => api.get<HotelOffer[]>(`/api/v1/partner/hotels/${hotel.id}/offers`),
    refetchInterval: 4000,
  });

  return (
    <>
      <PageHead title="Bid requests" sub="Review guest bids and respond before the deadline." />
      <WhatsAppPreference endpoint={`/api/v1/partner/hotels/${hotel.id}/whatsapp`} label="Receive bid alerts on WhatsApp" />
      <div className="partner-view-tabs" aria-label="Partner requests view">
        <button className="btn ghost" aria-pressed={section === 'requests'} onClick={() => setSection('requests')}><ListChecks size={16} /> Requests</button>
        <button className="btn ghost" aria-pressed={section === 'activity'} onClick={() => setSection('activity')}><MessageCircle size={16} /> Activity</button>
      </div>
      <div className={`split partner-split view-${section}`}>
        <div className="partner-requests-panel">
          <ErrorBox error={offers.error} />
          {!offers.isLoading && !offers.data?.length && (
            <div className="card">
              <h3>No bid requests waiting</h3>
              <p className="muted small">Guest bids that need a reply will appear here with a countdown.</p>
            </div>
          )}
          {offers.data?.map((o) => <RequestCard key={`${o.offerId}-${o.round}`} offer={o} now={now} />)}
        </div>
        <div className="partner-activity-panel"><PartnerActivity key={hotel.id} /></div>
      </div>
    </>
  );
}

function RequestCard({ offer, now }: { offer: HotelOffer; now: number }) {
  const { hotel } = useHotel();
  const api = useApi();
  const qc = useQueryClient();
  const [counter, setCounter] = useState('');
  const left = countdown(offer.respondBy, now);
  const below = Math.round((1 - offer.bidPricePerNight / offer.listedPrice) * 100);
  const counterNum = Number(counter);
  const belowMinimum = offer.bidPricePerNight < offer.minimumPrice;
  const counterOk = counterNum > offer.bidPricePerNight && counterNum >= offer.minimumPrice && counterNum <= offer.listedPrice;

  const respond = useMutation({
    mutationFn: (b: { action: HotelAction; counterPrice?: number }) =>
      api.post(`/api/v1/partner/hotels/${hotel.id}/offers/${offer.offerId}/respond`, { ...b, round: offer.round }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['offers', hotel.id] }); qc.invalidateQueries({ queryKey: ['chat', hotel.id] }); },
  });

  return (
    <div className="req">
      <div className="row spread">
        <b>{offer.roomType}</b>
        <span className={'badge ' + (left ? 'warn' : 'bad')}>{left ? `Reply within ${left}` : 'Time is up'}</span>
      </div>
      <div className="muted small">
        {fmtDate(offer.checkIn)} → {fmtDate(offer.checkOut)} · {offer.nights} night{offer.nights > 1 ? 's' : ''} · {offer.rooms} room{offer.rooms > 1 ? 's' : ''} · round {offer.round}
      </div>
      <div className="request-prices">
        <div><strong className="price">{inr(offer.bidPricePerNight)}</strong> <span className="small muted">guest bid per night</span>
          <div className="small muted">Private minimum {inr(offer.minimumPrice)}</div></div>
        <div><span className="small muted">Listed rate</span><br /><strong>{inr(offer.listedPrice)}</strong>
          <span className="small muted"> · {below}% lower bid</span></div>
      </div>
      {belowMinimum && <p className="small muted">Below your {inr(offer.minimumPrice)} minimum. Counter or decline.</p>}
      <ErrorBox error={respond.error ? new Error(errMsg(respond.error)) : null} />
      <div className="request-actions">
        <button className="btn ok" disabled={!left || belowMinimum || respond.isPending} onClick={() => respond.mutate({ action: 'ACCEPT' })}>Accept {inr(offer.bidPricePerNight)}</button>
        <button className="btn danger" disabled={!left || respond.isPending} onClick={() => respond.mutate({ action: 'DECLINE' })}>Decline</button>
      </div>
      <div className="request-counter">
        <label className="field">Counteroffer
          <input className="input" inputMode="numeric" placeholder={`Up to ${offer.listedPrice}`} value={counter}
            onChange={(event) => setCounter(event.target.value.replace(/\D/g, ''))} />
        </label>
        <button className="btn" disabled={!left || !counterOk || respond.isPending} onClick={() => respond.mutate({ action: 'COUNTER', counterPrice: counterNum })}>Send counter</button>
      </div>
    </div>
  );
}

/** *bold* like WhatsApp */
function rich(text: string) {
  return text.split(/(\*[^*]+\*)/g).map((p, i) => (p.startsWith('*') && p.endsWith('*') && p.length > 2 ? <b key={i}>{p.slice(1, -1)}</b> : <span key={i}>{p}</span>));
}

function PartnerActivity() {
  const { hotel } = useHotel();
  const api = useApi();
  const activity = useQuery({
    queryKey: ['chat', hotel.id],
    queryFn: () => api.get<ChatMessage[]>(`/api/v1/partner/hotels/${hotel.id}/chat`),
    refetchInterval: 4000,
  });

  return <section className="card partner-activity" aria-label="Bid activity">
    <h2>Bid activity</h2>
    <ErrorBox error={activity.error} />
    {activity.isLoading && <p role="status">Loading activity…</p>}
    {activity.data?.length === 0 && <p className="muted small">Bid replies and delivery updates will appear here.</p>}
    <ul>
      {activity.data?.slice().reverse().map((event) => <li key={event.id}>
        <div>{rich(event.body)}</div>
        <p className="muted small">
          {event.channel === 'WHATSAPP' ? 'WhatsApp' : event.sender === 'HOTEL' ? 'Partner portal' : 'BidStay'} · {fmtTime(event.at)}
          {event.whatsappStatus ? ` · WhatsApp ${event.whatsappStatus}` : ''}
        </p>
      </li>)}
    </ul>
  </section>;
}
