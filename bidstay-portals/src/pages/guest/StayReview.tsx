import { useEffect, useRef, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { X } from 'lucide-react';
import { ErrorBox } from '../../components/ui';
import { useApi } from '../../lib/api';
import { useSession } from '../../lib/session';
import { useGuest } from './GuestShell';
import type { PublicHotel } from './HotelDetails';
import { rupees } from './models';

export default function StayReview({ hotelId, hotelName, roomTypeId, roomName, dates, hourly,
  rooms, guests, total, busy, error, back, proceed }: {
  hotelId: number; hotelName: string; roomTypeId: number; roomName?: string; dates: ReactNode; hourly?: boolean;
  rooms: number; guests: number; total: number; busy: boolean; error: unknown; back: () => void; proceed: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const api = useApi();
  const { baseUrl } = useSession();
  const { signedIn, checkingSignIn, openSignIn } = useGuest();
  const { data } = useQuery({
    queryKey: ['hotel-details', baseUrl, hotelId],
    queryFn: () => api.get<PublicHotel>('/api/v1/hotels/' + hotelId),
  });
  useEffect(() => { dialog.current?.showModal(); }, []);
  const name = roomName ?? data?.rooms.find((room) => room.id === roomTypeId)?.name;
  const policy = data?.stayPolicy;

  return <dialog ref={dialog} className="guest-dialog guest-stay-review" aria-labelledby="guest-stay-review-title"
    onCancel={(event) => { if (busy) event.preventDefault(); else back(); }}>
    <button type="button" className="guest-close" disabled={busy} onClick={back} aria-label="Close stay review"><X size={21} /></button>
    <h2 id="guest-stay-review-title">Review your stay</h2>
    <h3>{hotelName}</h3>
    {name && <p>{name}</p>}
    <div className="guest-review-summary">
      <p>{dates}</p>
      <p>{rooms} {rooms === 1 ? 'room' : 'rooms'} · {guests} {guests === 1 ? 'guest' : 'guests'}</p>
      {policy && !hourly && <p>Check-in from {policy.checkInFrom} IST · Check-out by {policy.checkOutBy} IST</p>}
      {policy?.guestInstructions && <p className="guest-stay-instructions">{policy.guestInstructions}</p>}
      <p className="guest-review-total"><strong>Stay total</strong><strong>{rupees.format(total)}</strong></p>
    </div>
    <ErrorBox error={error} />
    <button type="button" className="guest-cta" disabled={busy || checkingSignIn} onClick={signedIn ? proceed : openSignIn}>
      {busy ? 'Checking availability…' : checkingSignIn ? 'Checking sign-in…' : signedIn ? 'Continue to checkout' : 'Sign in to continue'}
    </button>
    <button type="button" className="guest-quiet" disabled={busy} onClick={back}>Back</button>
  </dialog>;
}
