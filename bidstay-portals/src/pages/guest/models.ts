import type { StayPolicy } from '../../lib/types';

export interface HotelResult {
  id: number;
  name: string;
  area: string;
  starRating?: number;
  listedPrice: number;
  latitude?: number;
  longitude?: number;
  verified: boolean;
  hotelPhoto?: string;
  roomPhoto?: string;
}

export interface HotelPage { hotels: HotelResult[]; nextCursor?: number }

export interface Offer {
  offerId: number;
  hotelId: number;
  hotelName: string;
  area: string;
  status: string;
  pricePerNight?: number;
  totalForStay?: number;
  expiresAt?: string;
}

export interface Bid {
  bidId: number;
  status: string;
  round: number;
  maxRounds: number;
  pricePerNight: number;
  checkIn: string;
  checkOut: string;
  rooms: number;
  expiresAt: string;
  bookingId?: number;
  freeCancelUntil?: string;
  offers: Offer[];
}

export interface Booking {
  bookingId: number;
  status: string;
  checkIn: string;
  checkOut: string;
  rooms: number;
  pricePerNight: number;
  totalAmount: number;
  payBy: string;
  hotelName?: string;
  hotelAddress?: string;
  freeCancelUntil?: string;
  refundStatus?: string;
  nights: number;
  roomName?: string;
  stayPolicy?: StayPolicy;
  receipt?: { reference: string; recordedAt?: string; refundReference?: string; demo: boolean };
}

export const rupees = new Intl.NumberFormat('en-IN', {
  style: 'currency', currency: 'INR', minimumFractionDigits: 0, maximumFractionDigits: 2,
});

export const offerReady = (offer: Offer) => ['ACCEPTED', 'COUNTERED'].includes(offer.status);

export function addDays(iso: string, days: number) {
  const date = new Date(iso + 'T00:00:00Z');
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}


export const indiaDateTime = (iso: string) => new Intl.DateTimeFormat('en-IN', {
  dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Kolkata',
}).format(new Date(iso)) + ' IST';

export const refundLabel = (status: string) => ({
  REFUND_PENDING: 'Refund queued', REFUND_SUBMITTED: 'Refund processing',
  REFUND_PROCESSED: 'Refund processed', REFUND_REVIEW: 'Refund needs review',
}[status] ?? 'Refund status unavailable');


export function mediaUrl(api: string, path: string) {
  return api.replace(/\/$/, '') + path;
}
