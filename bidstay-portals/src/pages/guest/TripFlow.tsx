import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, ArrowRight, CalendarDays, Check, Clock3, CreditCard, Hotel, Images, MapPin, Printer } from 'lucide-react';
import { ErrorBox } from '../../components/ui';
import { useApi } from '../../lib/api';
import { countdown, fmtDate, useNow } from '../../lib/format';
import { useSession } from '../../lib/session';
import { useGuest } from './GuestShell';
import HotelDetails from './HotelDetails';
import WhatsAppPreference from '../WhatsAppPreference';
import { indiaDateTime, offerReady, refundLabel, rupees, type Bid, type Booking, type Offer } from './models';

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
          <span><strong>{fmtDate(bid.checkIn)} – {fmtDate(bid.checkOut)}</strong><small>{bid.rooms} {bid.rooms === 1 ? 'room' : 'rooms'} · Bid {rupees.format(bid.pricePerNight)} per room per night</small></span>
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
  const [details, setDetails] = useState<Offer>();
  const key = ['guest-bid', cacheKey, bidId];
  const result = useQuery({
    queryKey: key, enabled: signedIn && !!bidId,
    queryFn: () => api.get<Bid>('/api/v1/bids/' + bidId),
    refetchInterval: (query) => query.state.data?.status === 'OPEN' ? 4000 : false,
  });
  const raise = useMutation({
    mutationFn: () => api.post<Bid>('/api/v1/bids/' + bidId + '/raise', { pricePerNight: Number(raiseTo) }),
    onSuccess: (bid) => {
      qc.setQueryData(key, bid);
      void qc.invalidateQueries({ queryKey: ['guest-trips', cacheKey] });
      setRaiseTo('');
    },
  });
  const accept = useMutation({
    mutationFn: (offerId: number) => api.post<Booking>(`/api/v1/bids/${bidId}/offers/${offerId}/accept`),
    onSuccess: (booking) => { void qc.invalidateQueries({ queryKey: ['guest-trips'] }); navigate('/checkout/' + booking.bookingId); },
  });
  const withdraw = useMutation({
    mutationFn: () => api.post<Bid>(`/api/v1/bids/${bidId}/cancel`),
    onSuccess: (bid) => {
      qc.setQueryData(key, bid);
      void qc.invalidateQueries({ queryKey: ['guest-trips', cacheKey] });
    },
  });
  const bid = result.data;
  const time = bid && bid.status === 'OPEN' ? countdown(bid.expiresAt, now) : null;
  const busy = accept.isPending || raise.isPending || withdraw.isPending;
  const sorted = [...(bid?.offers ?? [])].sort((left, right) =>
    Number(offerReady(right)) - Number(offerReady(left)) || (left.totalForStay ?? Infinity) - (right.totalForStay ?? Infinity));
  const bookable = sorted.filter(offerReady);
  const lowest = bookable[0]?.totalForStay;
  const pending = sorted.filter((offer) => offer.status === 'PENDING'
    && (!offer.expiresAt || Date.parse(offer.expiresAt) > now)).length;

  return <TripPage title="Your hotel offers" sub={bid ? `${fmtDate(bid.checkIn)} – ${fmtDate(bid.checkOut)} · ${bid.rooms} ${bid.rooms === 1 ? 'room' : 'rooms'}` : undefined}>
    {!signedIn ? <SignInPrompt /> : <>
      <ErrorBox error={result.error || accept.error || raise.error || withdraw.error} />
      {result.isPending && <p role="status">Loading offers…</p>}
      {bid && <>
        <div className="guest-offer-summary">
          <div><small>Your bid per room per night</small><strong>{rupees.format(bid.pricePerNight)}</strong></div>
          <div><small>Round</small><strong>{bid.round} / {bid.maxRounds}</strong></div>
          <div><small>Bid status</small><strong>{time ? <><Clock3 size={18} /> {time} left</> : statusLabel(bid.status === 'OPEN' ? 'EXPIRED' : bid.status)}</strong></div>
        </div>
        {bid.freeCancelUntil && Date.parse(bid.freeCancelUntil) > now && <p className="guest-trip-description">Free cancellation until {indiaDateTime(bid.freeCancelUntil)}.</p>}
        {bid.bookingId && <Link className="guest-cta" to={'/checkout/' + bid.bookingId}>View booking <ArrowRight size={16} /></Link>}
        {time && sorted.length > 0 && <div className="guest-reply-progress">
          <p role="status">{bookable.length} {bookable.length === 1 ? 'offer' : 'offers'} ready · {pending} {pending === 1 ? 'hotel' : 'hotels'} still reviewing</p>
          <progress aria-label="Hotel requests resolved" value={sorted.length - pending} max={sorted.length} />
        </div>}
        <div className="guest-trip-list">
          {sorted.map((offer) => (
            <article className="guest-offer-row" key={offer.offerId}>
              <span className="guest-hotel-icon"><Hotel size={22} /></span>
              <div><h2>{offer.hotelName}</h2><p><MapPin size={14} /> {offer.area}</p>
                <span className={offerReady(offer) ? 'guest-state available' : 'guest-state'}>
                  {statusLabel(offer.status === 'PENDING' && offer.expiresAt && Date.parse(offer.expiresAt) <= now ? 'EXPIRED' : offer.status)}
                </span></div>
              <div className="guest-offer-amount">{offer.totalForStay != null && <><strong>{rupees.format(offer.totalForStay)}</strong>
                <small>{time && offerReady(offer) && bookable.length > 1 && offer.totalForStay === lowest ? 'Lowest stay total' : 'Full stay total'}</small></>}</div>
              <div className="guest-offer-actions">
                <button type="button" className="guest-quiet" onClick={() => setDetails(offer)}
                  aria-label={`Photos and rooms at ${offer.hotelName}`}><Images size={16} /> Photos and rooms</button>
                {time && offerReady(offer) && <button className="guest-cta" disabled={busy} onClick={() => accept.mutate(offer.offerId)}>Choose offer <ArrowRight size={16} /></button>}
              </div>
            </article>
          ))}
        </div>
        {time && bid.round < bid.maxRounds && bid.pricePerNight < 100_000 && <form className="guest-raise" onSubmit={(event) => { event.preventDefault(); raise.mutate(); }}>
          <label>Raise your rate per room per night<input type="number" min={(bid.pricePerNight + 0.01).toFixed(2)} max={100_000}
            step="0.01" required value={raiseTo} onChange={(event) => setRaiseTo(event.target.value)} /></label>
          <div className="guest-raise-choices">{[100, 200, 300].filter((amount) => bid.pricePerNight + amount <= 100_000).map((amount) =>
            <button key={amount} type="button" className="guest-quiet" onClick={() => setRaiseTo((bid.pricePerNight + amount).toFixed(2))}>+{rupees.format(amount)}</button>)}</div>
          <button className="guest-quiet" disabled={busy}>Send higher bid <ArrowRight size={16} /></button>
        </form>}
        {time && <button type="button" className="guest-quiet guest-withdraw" disabled={busy} onClick={() => {
          if (window.confirm('Withdraw this bid? Hotels will no longer be able to respond.')) withdraw.mutate();
        }}>Withdraw bid</button>}
        {bid.status === 'CANCELLED' && !bid.bookingId && <p role="status" className="guest-trip-description guest-withdraw">You withdrew this bid.</p>}
        {details && <HotelDetails key={details.hotelId} id={details.hotelId} name={details.hotelName} close={() => setDetails(undefined)} />}
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
  const freeCancellation = !!freeUntil && Date.parse(freeUntil) > now;
  const canCancel = pending || (confirmed && freeCancellation);
  const title = confirmed ? 'Booking confirmed' : pending ? 'Checkout' : booking ? statusLabel(booking.status) : 'Checkout';

  return <TripPage title={title} sub={booking ? `Booking #${booking.bookingId}` : undefined}>
    {!signedIn ? <SignInPrompt /> : <>
      <ErrorBox error={result.error ?? pay.error ?? paymentLink.error ?? cancel.error} />
      {result.isPending && <p role="status">Loading booking…</p>}
      {booking && <section className="guest-checkout">
        <span className={'guest-hotel-icon' + (confirmed ? ' confirmed' : '')}>{confirmed ? <Check size={25} /> : <CreditCard size={24} />}</span>
        <h2>{booking.hotelName ?? 'Stay summary'}</h2>
        {booking.hotelAddress && <p><MapPin size={15} /> {booking.hotelAddress}</p>}
        {booking.roomName && <p>{booking.roomName}</p>}
        <div className="guest-stay-dates">
          <div><span>Check-in</span><strong><CalendarDays size={15} /> {fmtDate(booking.checkIn)}</strong>
            {booking.stayPolicy?.checkInFrom && <small>From {booking.stayPolicy.checkInFrom} IST</small>}</div>
          <div><span>Check-out</span><strong><CalendarDays size={15} /> {fmtDate(booking.checkOut)}</strong>
            {booking.stayPolicy?.checkOutBy && <small>By {booking.stayPolicy.checkOutBy} IST</small>}</div>
        </div>
        <div className="guest-price-breakdown">
          <span>{rupees.format(booking.pricePerNight)} per room per night</span>
          <span>{booking.rooms} {booking.rooms === 1 ? 'room' : 'rooms'} × {booking.nights} {booking.nights === 1 ? 'night' : 'nights'}</span>
        </div>
        <div className="guest-checkout-total"><span>{booking.receipt ? 'Original payment' : 'Total for your stay'}</span><strong>{rupees.format(booking.totalAmount)}</strong></div>
        {(pending || confirmed || booking.stayPolicy?.guestInstructions) && <div className="guest-stay-terms">
          <h3>Stay terms</h3>
          {booking.stayPolicy?.guestInstructions && <p className="guest-stay-instructions">{booking.stayPolicy.guestInstructions}</p>}
          {(pending || confirmed) && <p>{freeCancellation ? `Free cancellation until ${indiaDateTime(freeUntil)}.`
            : freeUntil ? `Free cancellation ended on ${indiaDateTime(freeUntil)}.` : 'Free cancellation is unavailable after payment.'}</p>}
        </div>}
        {pending && <p><Clock3 size={15} /> {left ? `Rooms held for ${left}` : 'Room hold ended'}</p>}
        {left && options.data?.razorpay && (paymentLink.data
          ? <a href={paymentLink.data.url} target="_blank" rel="noreferrer" className="guest-cta">Open secure checkout <ArrowRight size={16} /></a>
          : <button className="guest-cta" disabled={paymentLink.isPending} onClick={() => paymentLink.mutate()}>{paymentLink.isPending ? 'Preparing checkout…' : 'Pay with Razorpay'}</button>)}
        {left && !options.data?.razorpay && import.meta.env.DEV && <button className="guest-cta" disabled={pay.isPending} onClick={() => pay.mutate()}>{pay.isPending ? 'Confirming…' : 'Complete demo payment'}</button>}
        {left && !import.meta.env.DEV && options.data && !options.data.razorpay && <p>Online payment is currently unavailable.</p>}
        {booking.receipt && <section className="guest-payment-receipt" aria-label="Payment receipt">
          <h3>{booking.receipt.demo ? 'Demo payment receipt' : 'Payment receipt'}</h3>
          <dl>
            <div><dt>Payment reference</dt><dd>{booking.receipt.reference}</dd></div>
            {booking.receipt.recordedAt && <div><dt>Payment recorded</dt><dd>{indiaDateTime(booking.receipt.recordedAt)}</dd></div>}
            {booking.receipt.refundReference && <div><dt>Refund reference</dt><dd>{booking.receipt.refundReference}</dd></div>}
          </dl>
        </section>}
        {booking.refundStatus && <p role="status">{refundLabel(booking.refundStatus)}</p>}
        <div className="guest-checkout-actions">
          {canCancel && <button className="guest-quiet" disabled={cancel.isPending} onClick={() => {
            if (window.confirm('Cancel this booking and release the rooms?')) cancel.mutate();
          }}>{pending ? 'Cancel checkout' : 'Cancel booking'}</button>}
          {booking.receipt && <button className="guest-quiet" onClick={() => window.print()}><Printer size={16} /> Print receipt</button>}
          {!pending && <Link to="/trips" className="guest-quiet">View my trips <ArrowRight size={17} /></Link>}
        </div>
      </section>}
    </>}
  </TripPage>;
}
