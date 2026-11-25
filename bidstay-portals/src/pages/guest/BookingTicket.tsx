import { useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, MessageCircle, Printer } from 'lucide-react';
import { ErrorBox } from '../../components/ui';
import { useApi } from '../../lib/api';
import { deviceRequest } from '../../lib/portal-device';
import { useSession } from '../../lib/session';
import { useGuest } from './GuestShell';
import TicketContactPreferences from './TicketContactPreferences';

type Delivery = { channels: { channel: string; state: string }[]; hotelPhone?: string; hotelId: number; confirmed: boolean; demo: boolean };
const channelLabel = (channel: string) => ({ SMS: 'SMS', EMAIL: 'Email', WHATSAPP: 'WhatsApp' })[channel] ?? channel;
const channelState = (state: string) => ({
  QUEUED: 'Queued', SUBMITTED: 'Sent to provider', SENT: 'Sent',
  DELIVERED: 'Delivered', READ: 'Read', FAILED: 'Failed', SKIPPED: 'Skipped',
})[state] ?? state.toLowerCase();

export default function BookingTicket() {
  const { bookingId } = useParams();
  const { signedIn, checkingSignIn, openSignIn } = useGuest();
  const { token, cacheKey } = useSession();
  const api = useApi();
  const client = useQueryClient();
  const frame = useRef<HTMLIFrameElement>(null);
  const [loaded, setLoaded] = useState(false);
  const [printError, setPrintError] = useState<unknown>();
  const endpoint = '/api/v1/bookings/' + bookingId + '/ticket';
  const key = ['ticket', cacheKey, bookingId];
  const deliveryKey = [...key, 'delivery'];
  const ticket = useQuery({
    queryKey: key, enabled: signedIn && !!bookingId, retry: false,
    queryFn: async () => (await api.download(endpoint)).text(),
  });
  const delivery = useQuery({
    queryKey: deliveryKey, enabled: signedIn && !!ticket.data, retry: false,
    queryFn: () => api.get<Delivery>(endpoint + '/delivery'),
    refetchInterval: (query) => query.state.data?.channels.some((channel) => channel.state === 'QUEUED') ? 4000 : false,
  });
  const queue = useMutation({
    mutationFn: () => api.post(endpoint + '/delivery'),
    onSuccess: () => { void client.invalidateQueries({ queryKey: deliveryKey }); },
  });

  function print() {
    if (!ticket.data) return;
    setPrintError(undefined);
    const text = frame.current?.contentDocument?.body.innerText.slice(0, 16_000) ?? 'Booking #' + bookingId;
    const onDevice = deviceRequest({ action: 'print', text, html: ticket.data });
    if (onDevice) void onDevice.catch(setPrintError);
    else frame.current?.contentWindow?.print();
  }

  const hotel = delivery.data;
  const hotelLink = hotel ? new URL('/hotel/bookings', window.location.origin) : undefined;
  if (hotelLink && hotel) {
    hotelLink.searchParams.set('hotel', String(hotel.hotelId));
    hotelLink.searchParams.set('booking', String(bookingId));
  }
  const share = hotel?.confirmed && hotelLink
    ? 'https://wa.me/' + (hotel.hotelPhone ? '91' + hotel.hotelPhone : '')
        + '?text=' + encodeURIComponent('Hello, I have a ' + (hotel.demo ? 'demo' : 'confirmed') + ' BidStay booking #' + bookingId
          + '. Open it in the BidStay hotelier portal: ' + hotelLink.toString())
    : undefined;

  return <main className="guest-container guest-trip">
    <Link to={'/checkout/' + bookingId} className="guest-back"><ArrowLeft size={16} /> Booking details</Link>
    <h1>Your booking ticket</h1>
    {checkingSignIn ? <p role="status">Checking sign-in…</p> : !signedIn ? <div className="guest-empty">
      <h3>Sign in to open your ticket</h3><p>Use the mobile number you used for the booking.</p>
      <button className="guest-cta" onClick={openSignIn}>Sign in</button>
    </div> : <>
      <ErrorBox error={ticket.error ?? delivery.error ?? queue.error ?? printError} />
      {ticket.isPending && <p role="status">Loading your ticket…</p>}
      {ticket.data && <>
        <div className="guest-ticket-actions">
          <button className="guest-cta" disabled={!loaded} onClick={print}><Printer size={16} /> Print ticket</button>
          {share && <a className="guest-quiet" href={share} target="_blank" rel="noreferrer">
            <MessageCircle size={16} /> {hotel?.hotelPhone ? 'WhatsApp the hotel' : 'Share booking on WhatsApp'}</a>}
        </div>
        <iframe ref={frame} title="BidStay booking ticket" className="guest-ticket-frame" sandbox="allow-same-origin allow-modals"
          srcDoc={ticket.data} onLoad={() => setLoaded(true)} />
        {token && hotel?.confirmed && !hotel.demo && <section className="guest-ticket-contacts" aria-label="Ticket delivery status">
          <TicketContactPreferences />
          {hotel.channels.length > 0 && <dl className="guest-ticket-delivery">{hotel.channels.map((channel) =>
            <div key={channel.channel}><dt>{channelLabel(channel.channel)}</dt><dd>{channelState(channel.state)}</dd></div>)}</dl>}
          <button className="guest-quiet" disabled={queue.isPending} onClick={() => queue.mutate()}>Send ticket to saved contacts</button>
          {queue.isSuccess && <p role="status">{hotel.channels.length ? 'Delivery status shown above.' : 'Add and verify a contact to receive this ticket.'}</p>}
        </section>}
      </>}
    </>}
  </main>;
}
