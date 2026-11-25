import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { useApi } from '../../lib/api';
import { fmtDate, fmtIndiaTime, inr } from '../../lib/format';
import type { PartnerBooking } from '../../lib/types';
import { ErrorBox, Loading, PageHead } from '../../components/ui';
import { useHotel } from './HotelContext';

export default function Bookings() {
  const { hotel } = useHotel();
  const api = useApi();
  const [params] = useSearchParams();
  const bookingId = params.get('booking');
  const propertyId = params.get('hotel');
  const valid = (!propertyId || propertyId === String(hotel.id)) && (!bookingId || /^\d{1,18}$/.test(bookingId));
  const q = useQuery({
    queryKey: ['bookings', hotel.id, bookingId], enabled: valid,
    queryFn: () => api.get<PartnerBooking[]>(`/api/v1/partner/hotels/${hotel.id}/bookings` + (bookingId ? '?bookingId=' + bookingId : '')),
  });
  const total = (q.data ?? []).reduce((s, b) => s + b.payout, 0);
  return (
    <>
      <PageHead title={bookingId ? 'Booking #' + bookingId : 'Bookings'} sub="Paid bookings. Guest contact appears only after payment." />
      {bookingId && <p><Link to="/hotel/bookings">View all bookings</Link></p>}
      {!valid && <div className="card">This booking is unavailable for the active property.</div>}
      <ErrorBox error={q.error} />
      {q.isLoading && <Loading />}
      {valid && q.data && (
        <div className="card">
          {!q.data.length ? <p className="muted">{bookingId ? 'This confirmed booking was not found for this property.' : 'No paid bookings yet.'}</p> : (
            <div className="table-wrap"><table>
              <thead><tr><th>#</th><th>Guest</th><th>Room</th><th>Stay</th><th className="right">Rate</th><th className="right">Total</th><th className="right">BidStay fee</th><th className="right">Your payout</th></tr></thead>
              <tbody>{q.data.map((b) => (
                <tr key={b.bookingId}>
                  <td>{b.bookingId}</td>
                  <td><b>{b.guestName}</b><div className="muted small">+91 {b.guestPhone}</div></td>
                  <td>{b.roomType}<div className="muted small">{b.rooms} room{b.rooms > 1 ? 's' : ''}</div></td>
                  <td>{b.arrival && b.departure ? `${fmtDate(b.checkIn)} · ${fmtIndiaTime(b.arrival)}–${fmtIndiaTime(b.departure)} IST`
                    : `${fmtDate(b.checkIn)} → ${fmtDate(b.checkOut)}`}</td>
                  <td className="right">{b.hourlyRate ? `${inr(b.hourlyRate)} / stay` : `${inr(b.pricePerNight)} / night`}</td><td className="right">{inr(b.total)}</td>
                  <td className="right muted">{inr(b.commission)}</td><td className="right bold">{inr(b.payout)}</td>
                </tr>))}</tbody>
              <tfoot><tr><td colSpan={7} className="right muted">Total payout</td><td className="right bold">{inr(total)}</td></tr></tfoot>
            </table></div>)}
        </div>)}
    </>
  );
}
