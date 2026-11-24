import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, ArrowRight, CalendarDays, Check, Clock3, CreditCard, Hotel, MapPin } from 'lucide-react';
import { ErrorBox } from '../../components/ui';
import { useApi } from '../../lib/api';
import { countdown, fmtDate, useNow } from '../../lib/format';
import { useSession } from '../../lib/session';
import { useGuest } from './GuestShell';
import WhatsAppPreference from '../WhatsAppPreference';
import { indiaDateTime, offerReady, refundLabel, rupees, type Bid, type Booking } from './models';

function TripPage({ title, sub, children }: { title: string; sub?: string; children: ReactNode }) {
  return (
    <main className="guest-container guest-trip">
      <Link to="/" className="guest-back"><ArrowLeft size={16} /> Find another stay</Link>
      <h1>{title}</h1>
      {sub && <p className="guest-trip-description">{sub}</p>}
      {children}
    </main>
  );
}

function SignInPrompt() {
  const { openSignIn } = useGuest();
  return <div className="guest-empty"><h3>Sign in to see your trips</h3><p>Your bids, offers and bookings will appear here.</p><button className="guest-cta" onClick={openSignIn}>Sign in</button></div>;
}

const statusLabel = (status: string) => ({
  OPEN: 'Waiting for offers', HELD: 'Payment pending', BOOKED: 'Booked', CANCELLED: 'Cancelled',
  ACCEPTED: 'Accepted', COUNTERED: 'Counteroffer', PENDING: 'Waiting', DECLINED: 'Declined',
  EXPIRED: 'Expired', UNAVAILABLE: 'Unavailable', SELECTED: 'Selected', CONFIRMED: 'Confirmed',
}[status] ?? status.toLowerCase().replaceAll('_', ' '));

export function Trips() {
  const { signedIn } = useGuest();
  const { cacheKey } = useSession();
  const api = useApi();
  const trips = useQuery({ queryKey: ['guest-trips', cacheKey], enabled: signedIn, queryFn: () => api.get<Bid[]>('/api/v1/bids') });
  return <TripPage title="My trips" sub="Track your bids and revisit your bookings.">
    {!signedIn ? <SignInPrompt /> : <>
      <WhatsAppPreference endpoint="/api/v1/whatsapp/guest" label="Get trip updates on WhatsApp" />
      <ErrorBox error={trips.error} />
      {trips.isPending && <p role="status">Loading trips…</p>}
      {trips.data?.length === 0 && <div className="guest-empty"><CalendarDays size={24} /><h3>No trips yet</h3><Link to="/" className="guest-cta">Find hotels</Link></div>}
      <div className="guest-trip-list">{trips.data?.map((bid) => (
        <Link className="guest-trip-row" key={bid.bidId} to={bid.bookingId ? '/checkout/' + bid.bookingId : '/offers/' + bid.bidId}>
          <span className="guest-hotel-icon"><CalendarDays size={21} /></span>
          <span><strong>{fmtDate(bid.checkIn)} – {fmtDate(bid.checkOut)}</strong><small>{bid.rooms} {bid.rooms === 1 ? 'room' : 'rooms'} · {rupees.format(bid.pricePerNight)} per room per night</small></span>
          <span className="guest-state">{statusLabel(bid.status)}</span>
          <ArrowRight size={18} />
        </Link>
      ))}</div>
    </>}
  </TripPage>;
}

export function Offers() {
  const { bidId } = useParams();
  const { signedIn } = useGuest();
  const { cacheKey } = useSession();
  const api = useApi();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const now = useNow();
  const [raiseTo, setRaiseTo] = useState('');
  const key = ['guest-bid', cacheKey, bidId];
  const result = useQuery({
    queryKey: key, enabled: signedIn && !!bidId,
    queryFn: () => api.get<Bid>('/api/v1/bids/' + bidId),
    refetchInterval: (query) => query.state.data?.status === 'OPEN' ? 4000 : false,
  });
  const raise = useMutation({
    mutationFn: () => api.post<Bid>('/api/v1/bids/' + bidId + '/raise', { pricePerNight: Number(raiseTo) }),
    onSuccess: (bid) => { qc.setQueryData(key, bid); setRaiseTo(''); },
  });
  const accept = useMutation({
    mutationFn: (offerId: number) => api.post<Booking>(`/api/v1/bids/${bidId}/offers/${offerId}/accept`),
    onSuccess: (booking) => { void qc.invalidateQueries({ queryKey: ['guest-trips'] }); navigate('/checkout/' + booking.bookingId); },
  });
  const bid = result.data;
  const time = bid && bid.status === 'OPEN' ? countdown(bid.expiresAt, now) : null;
  const sorted = [...(bid?.offers ?? [])].sort((left, right) =>
    Number(offerReady(right)) - Number(offerReady(left)) || (left.totalForStay ?? Infinity) - (right.totalForStay ?? Infinity));

  return <TripPage title="Your hotel offers" sub={bid ? `${fmtDate(bid.checkIn)} – ${fmtDate(bid.checkOut)} · ${bid.rooms} ${bid.rooms === 1 ? 'room' : 'rooms'}` : undefined}>
    {!signedIn ? <SignInPrompt /> : <>
      <ErrorBox error={result.error || accept.error || raise.error} />
      {result.isPending && <p role="status">Loading offers…</p>}
      {bid && <>
        <div className="guest-offer-summary">
          <div><small>Your bid per room per night</small><strong>{rupees.format(bid.pricePerNight)}</strong></div>
          <div><small>Round</small><strong>{bid.round} / {bid.maxRounds}</strong></div>
          <div><small>Bid status</small><strong>{time ? <><Clock3 size={18} /> {time} left</> : statusLabel(bid.status)}</strong></div>
        </div>
        {bid.freeCancelUntil && Date.parse(bid.freeCancelUntil) > now && <p className="guest-trip-description">Free cancellation until {indiaDateTime(bid.freeCancelUntil)}.</p>}
        {bid.bookingId && <Link className="guest-cta" to={'/checkout/' + bid.bookingId}>View booking <ArrowRight size={16} /></Link>}
        <div className="guest-trip-list">
          {sorted.map((offer) => (
            <article className="guest-offer-row" key={offer.offerId}>
              <span className="guest-hotel-icon"><Hotel size={22} /></span>
              <div><h2>{offer.hotelName}</h2><p><MapPin size={14} /> {offer.area}</p><span className={offerReady(offer) ? 'guest-state available' : 'guest-state'}>{statusLabel(offer.status)}</span></div>
              <div className="guest-offer-amount">{offer.totalForStay != null && <><strong>{rupees.format(offer.totalForStay)}</strong><small>Full stay total</small></>}</div>
              {time && offerReady(offer) && <button className="guest-cta" disabled={accept.isPending} onClick={() => accept.mutate(offer.offerId)}>Choose offer <ArrowRight size={16} /></button>}
            </article>
          ))}
        </div>
        {time && bid.round < bid.maxRounds && <form className="guest-raise" onSubmit={(event) => { event.preventDefault(); raise.mutate(); }}><label>Raise your rate per room per night<input type="number" min={bid.pricePerNight + 0.01} max={100_000} step="0.01" required value={raiseTo} onChange={(event) => setRaiseTo(event.target.value)} /></label><button className="guest-quiet" disabled={raise.isPending}>Send higher bid <ArrowRight size={16} /></button></form>}
      </>}
    </>}
  </TripPage>;
}

export function Checkout() {
  const { bookingId } = useParams();
  const { signedIn } = useGuest();
  const { cacheKey } = useSession();
  const api = useApi();
  const qc = useQueryClient();
  const now = useNow();
  const key = ['guest-booking', cacheKey, bookingId];
  const result = useQuery({
    queryKey: key, enabled: signedIn && !!bookingId,
    queryFn: () => api.get<Booking>('/api/v1/bookings/' + bookingId),
    refetchInterval: (query) => {
      const booking = query.state.data;
      return booking?.status === 'PENDING_PAYMENT' || (booking?.refundStatus && ['REFUND_PENDING', 'REFUND_SUBMITTED'].includes(booking.refundStatus)) ? 5000 : false;
    },
  });
  const cancel = useMutation({
    mutationFn: () => api.post<Booking>(`/api/v1/bookings/${bookingId}/cancel`),
    onSuccess: (booking) => { qc.setQueryData(key, booking); void qc.invalidateQueries({ queryKey: ['guest-trips'] }); },
  });
  const options = useQuery({ queryKey: ['payment-options', cacheKey], enabled: signedIn, queryFn: () => api.get<{ razorpay: boolean }>('/api/v1/payments/options') });
  const paymentLink = useMutation({ mutationFn: () => api.post<{ url: string }>(`/api/v1/bookings/${bookingId}/payment-link`) });
  const pay = useMutation({
    mutationFn: () => api.post<Booking>(`/api/v1/dev/bookings/${bookingId}/confirm-payment`, { paymentRef: 'web_demo_' + Date.now() }),
    onSuccess: (booking) => { qc.setQueryData(key, booking); void qc.invalidateQueries({ queryKey: ['guest-trips'] }); },
  });
  const booking = result.data;
  const pending = booking?.status === 'PENDING_PAYMENT';
  const left = pending ? countdown(booking.payBy, now) : null;
  const confirmed = booking?.status === 'CONFIRMED';
  const freeUntil = booking?.freeCancelUntil;
  const canCancel = pending || (confirmed && !!freeUntil && Date.parse(freeUntil) > now);

  return <TripPage title={confirmed ? 'Booking confirmed' : 'Checkout'} sub={booking ? `Booking #${booking.bookingId}` : undefined}>
    {!signedIn ? <SignInPrompt /> : <>
      <ErrorBox error={result.error ?? pay.error ?? paymentLink.error ?? cancel.error} />
      {result.isPending && <p role="status">Loading booking…</p>}
      {booking && <section className="guest-checkout">
        <span className={'guest-hotel-icon' + (confirmed ? ' confirmed' : '')}>{confirmed ? <Check size={25} /> : <CreditCard size={24} />}</span>
        <h2>{booking.hotelName ?? 'Stay summary'}</h2>
        {booking.hotelAddress && <p><MapPin size={15} /> {booking.hotelAddress}</p>}
        <p><CalendarDays size={15} /> {fmtDate(booking.checkIn)} to {fmtDate(booking.checkOut)} · {booking.rooms} {booking.rooms === 1 ? 'room' : 'rooms'}</p>
        <div className="guest-checkout-total"><span>Total for your stay</span><strong>{rupees.format(booking.totalAmount)}</strong></div>
        {!confirmed && <p><Clock3 size={15} /> {pending ? left ? `Rooms held for ${left}` : 'Room hold ended' : statusLabel(booking.status)}</p>}
        {left && options.data?.razorpay && (paymentLink.data
          ? <a href={paymentLink.data.url} target="_blank" rel="noreferrer" className="guest-cta">Open secure checkout <ArrowRight size={16} /></a>
          : <button className="guest-cta" disabled={paymentLink.isPending} onClick={() => paymentLink.mutate()}>{paymentLink.isPending ? 'Preparing checkout…' : 'Pay with Razorpay'}</button>)}
        {left && !options.data?.razorpay && import.meta.env.DEV && <button className="guest-cta" disabled={pay.isPending} onClick={() => pay.mutate()}>{pay.isPending ? 'Confirming…' : 'Complete demo payment'}</button>}
        {left && !import.meta.env.DEV && options.data && !options.data.razorpay && <p>Online payment is currently unavailable.</p>}
        {freeUntil && Date.parse(freeUntil) > now && (confirmed || pending) && <p>Free cancellation until {indiaDateTime(freeUntil)}.</p>}
        {booking.refundStatus && <p role="status">{refundLabel(booking.refundStatus)}</p>}
        {canCancel && <button className="guest-quiet" style={{ margin: '9px 10px 0 0' }} disabled={cancel.isPending} onClick={() => {
          if (window.confirm('Cancel this booking and release the rooms?')) cancel.mutate();
        }}>{pending ? 'Cancel checkout' : 'Cancel booking'}</button>}
        {confirmed && <Link to="/trips" className="guest-cta">View my trips <ArrowRight size={17} /></Link>}
      </section>}
    </>}
  </TripPage>;
}
