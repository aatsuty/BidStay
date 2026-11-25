import RecordCell from '../../components/RecordCell';
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
            <div className="table-wrap"><table className="record-table" role="table">
              <thead role="rowgroup"><tr role="row"><th role="columnheader" scope="col">#</th><th role="columnheader" scope="col">Guest</th><th role="columnheader" scope="col">Room</th><th role="columnheader" scope="col">Stay</th><th role="columnheader" scope="col" className="right">Rate</th><th role="columnheader" scope="col" className="right">Total</th><th role="columnheader" scope="col" className="right">BidStay fee</th><th role="columnheader" scope="col" className="right">Your payout</th></tr></thead>
              <tbody role="rowgroup">{q.data.map((b) => (
                <tr role="row" key={b.bookingId}>
                  <RecordCell label="Booking ID">{b.bookingId}</RecordCell>
                  <RecordCell label="Guest"><b>{b.guestName}</b><div className="muted small">+91 {b.guestPhone}</div></RecordCell>
                  <RecordCell label="Room">{b.roomType}<div className="muted small">{b.rooms} room{b.rooms > 1 ? 's' : ''}</div></RecordCell>
                  <RecordCell label="Stay">{b.arrival && b.departure ? `${fmtDate(b.checkIn)} · ${fmtIndiaTime(b.arrival)}–${fmtIndiaTime(b.departure)} IST`
                    : `${fmtDate(b.checkIn)} → ${fmtDate(b.checkOut)}`}</RecordCell>
                  <RecordCell label="Rate" className="right">{b.hourlyRate ? `${inr(b.hourlyRate)} / stay` : `${inr(b.pricePerNight)} / night`}</RecordCell><RecordCell label="Total" className="right">{inr(b.total)}</RecordCell>
                  <RecordCell label="BidStay fee" className="right muted">{inr(b.commission)}</RecordCell><RecordCell label="Your payout" className="right bold">{inr(b.payout)}</RecordCell>
                </tr>))}</tbody>
              <tfoot role="rowgroup"><tr role="row"><td role="cell" colSpan={7} className="right muted">Total payout</td><td role="cell" className="right bold">{inr(total)}</td></tr></tfoot>
            </table></div>)}
        </div>)}
    </>
  );
}
