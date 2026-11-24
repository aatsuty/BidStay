import { useEffect, useRef, useState } from 'react';
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
  const [section, setSection] = useState<'requests' | 'chat'>('requests');
  const offers = useQuery({
    queryKey: ['offers', hotel.id],
    queryFn: () => api.get<HotelOffer[]>(`/api/v1/partner/hotels/${hotel.id}/offers`),
    refetchInterval: 4000,
  });

  return (
    <>
      <PageHead title="Bid requests" sub="Review bids and reply from your partner inbox." />
      <div className="partner-view-tabs" aria-label="Partner requests view">
        <button className="btn ghost" aria-pressed={section === 'requests'} onClick={() => setSection('requests')}><ListChecks size={16} /> Requests</button>
        <button className="btn ghost" aria-pressed={section === 'chat'} onClick={() => setSection('chat')}><MessageCircle size={16} /> Inbox</button>
      </div>
      <div className={`split partner-split view-${section}`}>
        <div className="partner-requests-panel">
          <ErrorBox error={offers.error} />
          {!offers.isLoading && !offers.data?.length && (
            <div className="card">
              <h3>No bid requests waiting</h3>
              <p className="muted small">Guest bids below your auto-accept rules show up here with a countdown, and in the partner chat.</p>
            </div>
          )}
          {offers.data?.map((o) => <RequestCard key={`${o.offerId}-${o.round}`} offer={o} now={now} />)}
        </div>
        <div className="partner-chat-panel"><WhatsAppChat /></div>
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
      <div className="row" style={{ alignItems: 'baseline', margin: '10px 0' }}>
        <span className="price">{inr(offer.bidPricePerNight)}</span>
        <span className="muted">per night · your listed price {inr(offer.listedPrice)} ({below}% lower)</span>
      </div>
      {belowMinimum && <p className="small muted">Below your {inr(offer.minimumPrice)} minimum. Counter or decline.</p>}
      <ErrorBox error={respond.error ? new Error(errMsg(respond.error)) : null} />
      <div className="row wrap">
        <button className="btn ok" disabled={!left || belowMinimum || respond.isPending} onClick={() => respond.mutate({ action: 'ACCEPT' })}>Accept {inr(offer.bidPricePerNight)}</button>
        <button className="btn danger" disabled={!left || respond.isPending} onClick={() => respond.mutate({ action: 'DECLINE' })}>Decline</button>
        <span className="grow" />
        <input className="input" style={{ width: 160 }} inputMode="numeric" placeholder={`Counter up to ${offer.listedPrice}`} value={counter} onChange={(e) => setCounter(e.target.value.replace(/\D/g, ''))} />
        <button className="btn soft" disabled={!left || !counterOk || respond.isPending} onClick={() => respond.mutate({ action: 'COUNTER', counterPrice: counterNum })}>Send counter</button>
      </div>
    </div>
  );
}

/** *bold* like WhatsApp */
function rich(text: string) {
  return text.split(/(\*[^*]+\*)/g).map((p, i) => (p.startsWith('*') && p.endsWith('*') && p.length > 2 ? <b key={i}>{p.slice(1, -1)}</b> : <span key={i}>{p}</span>));
}

function WhatsAppChat() {
  const { hotel } = useHotel();
  const api = useApi();
  const qc = useQueryClient();
  const [text, setText] = useState('');
  const bodyRef = useRef<HTMLDivElement>(null);

  const chat = useQuery({
    queryKey: ['chat', hotel.id],
    queryFn: () => api.get<ChatMessage[]>(`/api/v1/partner/hotels/${hotel.id}/chat`),
    refetchInterval: 3000,
  });
  const send = useMutation({
    mutationFn: (t: string) => api.post<ChatMessage[]>(`/api/v1/partner/hotels/${hotel.id}/chat`, { text: t }),
    onSuccess: () => { setText(''); qc.invalidateQueries({ queryKey: ['chat', hotel.id] }); qc.invalidateQueries({ queryKey: ['offers', hotel.id] }); },
  });

  const last = chat.data?.[chat.data.length - 1]?.id;
  useEffect(() => { bodyRef.current?.scrollTo({ top: bodyRef.current.scrollHeight }); }, [last]);

  const submit = (t: string) => { if (t.trim() && !send.isPending) send.mutate(t.trim()); };

  return (
    <div className="wa">
      <div className="wa-head">
        <div className="wa-avatar">B</div>
        <div><b>BidStay</b><small>Partner inbox · {hotel.name}</small></div>
        <WhatsAppPreference endpoint={`/api/v1/partner/hotels/${hotel.id}/whatsapp`} label="WhatsApp alerts" compact />
      </div>
      <div className="wa-body" ref={bodyRef}>
        {chat.isLoading && <span className="muted small">Loading chat…</span>}
        {chat.data?.map((m) => (
          <div key={m.id} className={'bubble ' + (m.sender === 'HOTEL' ? 'me' : '') + (m.kind === 'REQUEST' ? ' is-request' : '')}>
            {rich(m.body)}
            <span className="t">{m.channel === 'WHATSAPP' ? 'WhatsApp · ' : ''}{fmtTime(m.at)}{m.whatsappStatus ? ` · WhatsApp ${m.whatsappStatus}` : ''}</span>
          </div>
        ))}
      </div>
      {send.error ? <div className="error" style={{ margin: 0, borderRadius: 0 }}>{errMsg(send.error)}</div> : null}
      <form className="wa-input" onSubmit={(e) => { e.preventDefault(); submit(text); }}>
        <input value={text} onChange={(e) => setText(e.target.value)} placeholder="Reply using the request reference" maxLength={200} />
        <button type="submit" aria-label="Send" disabled={send.isPending}>➤</button>
      </form>
    </div>
  );
}
