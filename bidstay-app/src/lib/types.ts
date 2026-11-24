// Mirrors the Spring Boot DTOs (BidDtos.java, BookingView.java, HotelSummary.java).

export type RoomCategory = 'STANDARD' | 'DELUXE' | 'SUITE';

export type BidStatus = 'OPEN' | 'HELD' | 'BOOKED' | 'CANCELLED' | 'EXPIRED';

export type OfferStatus =
  | 'PENDING'
  | 'ACCEPTED'
  | 'COUNTERED'
  | 'DECLINED'
  | 'EXPIRED'
  | 'UNAVAILABLE'
  | 'CANCELLED'
  | 'SELECTED';

export type BookingStatus = 'PENDING_PAYMENT' | 'CONFIRMED' | 'EXPIRED' | 'CANCELLED';

export interface HotelSummary {
  id: number;
  name: string;
  area: string;
  starRating: number | null;
  category: RoomCategory;
  listedPrice: number;
  hotelPhoto?: string;
  roomPhoto?: string;
}

export interface OfferView {
  offerId: number;
  hotelId: number;
  hotelName: string;
  area: string;
  status: OfferStatus;
  pricePerNight?: number;
  totalForStay?: number;
  autoAccepted: boolean;
  expiresAt?: string;
}

export interface BidView {
  bidId: number;
  status: BidStatus;
  round: number;
  maxRounds: number;
  pricePerNight: number;
  checkIn: string;
  checkOut: string;
  rooms: number;
  expiresAt: string;
  bookingId?: number;
  offers: OfferView[];
}

export interface BookingView {
  bookingId: number;
  status: BookingStatus;
  checkIn: string;
  checkOut: string;
  rooms: number;
  pricePerNight: number;
  totalAmount: number;
  payBy: string;
  hotelName?: string;
  hotelAddress?: string;
}

export interface HotelOfferView {
  offerId: number;
  roomType: string;
  bidPricePerNight: number;
  listedPrice: number;
  checkIn: string;
  checkOut: string;
  rooms: number;
  nights: number;
  round: number;
  respondBy: string;
}

export interface CreateBidInput {
  city: string;
  checkIn: string;
  checkOut: string;
  rooms: number;
  guests: number;
  category: RoomCategory;
  pricePerNight: number;
  hotelIds: number[];
}
