// Mirrors the backend DTOs (PartnerDtos, AdminDtos, BidDtos, MeController).
export type Role = 'GUEST' | 'HOTEL_STAFF' | 'ADMIN';
export type HotelStatus = 'PENDING_VERIFICATION' | 'ACTIVE' | 'REJECTED' | 'SUSPENDED';
export type RoomCategory = 'STANDARD' | 'DELUXE' | 'SUITE';
export type DocType = 'GST' | 'LICENCE' | 'OWNER_ID' | 'PHOTOS';

export interface Me { id: number; name: string; phone: string; role: Role }
export interface MyHotel { id: number; name: string; area: string; status: HotelStatus; rejectionReason?: string }

export interface HotelOffer {
  offerId: number; roomType: string; bidPricePerNight: number; listedPrice: number;
  checkIn: string; checkOut: string; rooms: number; nights: number; round: number; respondBy: string;
}
export type HotelAction = 'ACCEPT' | 'COUNTER' | 'DECLINE';

export interface ChatMessage { id: number; sender: 'BOT' | 'HOTEL'; kind: 'REQUEST' | 'SYSTEM' | 'REPLY'; body: string; offerId?: number; at: string }

export interface RoomRate { roomTypeId: number; category: RoomCategory; name: string; listedPrice: number; minPrice: number; totalRooms: number }
export interface Rates { autoMode: boolean; autoCounter: boolean; autoCounterPct: number; autoDecline: boolean; rooms: RoomRate[] }

export interface Night { date: string; total: number; held: number; sold: number }
export interface RoomAvailability { roomTypeId: number; name: string; nights: Night[] }

export interface PartnerBooking {
  bookingId: number; status: string; roomType: string; checkIn: string; checkOut: string; rooms: number;
  pricePerNight: number; total: number; commission: number; payout: number; guestName: string; guestPhone: string;
}
export interface Dashboard {
  pendingReplies: number; bidsLast30Days: number; acceptedLast30Days: number; counteredLast30Days: number;
  declinedOrExpiredLast30Days: number; avgReplySeconds?: number; confirmedBookingsLast30Days: number;
  payoutLast30Days: number; commissionLast30Days: number; upcomingStays: number;
}
export interface DocumentView { type: DocType; submitted: boolean; verified: boolean; reference?: string }

export interface RoomInput { category: RoomCategory; name: string; maxGuests: number; listedPrice: number; minPrice: number; totalRooms: number }
export interface RegisterHotel {
  name: string; city: string; area: string; address: string; starRating: number; gstNumber?: string; contactPhone?: string; rooms: RoomInput[];
}

// ---- admin
export interface Target { key: string; label: string; actualPct?: number; targetPct: number; basis: string }
export interface AdminStats {
  liveHotels: number; pendingHotels: number; totalBids: number; confirmedBookings: number;
  bookingValue: number; commissionEarned: number; targets: Target[];
}
export interface AdminHotel {
  id: number; name: string; city: string; area: string; status: HotelStatus; rejectionReason?: string;
  contactPhone?: string; gstNumber?: string; roomTypes: number; createdAt: string; allDocsVerified: boolean; documents: DocumentView[];
}
export interface AdminBooking { id: number; hotel: string; guest: string; status: string; checkIn: string; checkOut: string; rooms: number; total: number; commission: number; createdAt: string }
export interface AdminBid { id: number; guest: string; category: string; checkIn: string; checkOut: string; rooms: number; pricePerNight: number; round: number; status: string; offers: number; offersReady: number; createdAt: string }
