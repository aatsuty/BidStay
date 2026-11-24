# BidStay backend (MVP foundation)

Java 21, Spring Boot 3.3, PostgreSQL 16, Flyway. Modular monolith.

## Run it

```bash
docker compose up -d                       # PostgreSQL
mvn spring-boot:run -Dspring-boot.run.profiles=dev
mvn verify                                 # integration tests (needs Docker, uses Testcontainers)
```
The `dev` profile also loads demo data: 4 Delhi hotels, 6 room types, 60 days of inventory,
guests `X-User-Id: 1` and `2`, and a hotel owner for hotels 1 and 2 (`X-User-Id: 3`).

## Guest phone sign-in

Configure a Twilio Verify service for six-digit SMS codes and enable the India destination.
Set `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, and `TWILIO_VERIFY_SERVICE_SID`
in the **backend** environment, restart it, and use **Profile** in the guest app.
Guest signup takes place after the phone is verified. Sessions expire after seven days
and are revoked on sign-out. The guest app keeps the token in memory on web and uses
SecureStore on native. For web deployment, serve the frontend and API under the same
HTTPS origin or configure an explicit allowed frontend origin.

Local demo accounts work with the `dev` profile without Twilio. Outside `dev`, the
API rejects demo identity headers. Hotel and admin production sign-in is pending.
Before enabling SMS for a public pilot, configure Twilio Fraud Guard, SMS geography
permissions, and rate limits at the public API gateway.

## Try the full flow (curl)

```bash
D1=$(date -d "+5 days" +%F); D2=$(date -d "+7 days" +%F)

# 0. Guest browses hotels (no minimum prices exposed)
curl "localhost:8080/api/v1/hotels?city=Delhi&category=STANDARD"

# 1. Guest bids 1100/night to hotels 1 and 2. Hotel 2 (min 1000) auto-accepts, hotel 1 (min 1200) waits.
curl -X POST localhost:8080/api/v1/bids -H 'X-User-Id: 1' -H 'Content-Type: application/json' \
  -d "{\"city\":\"Delhi\",\"checkIn\":\"$D1\",\"checkOut\":\"$D2\",\"rooms\":1,\"guests\":2,\"category\":\"STANDARD\",\"pricePerNight\":1100,\"hotelIds\":[1,2]}"

# 2. Hotel 1 sees the pending bid and counters 1500
curl localhost:8080/api/v1/partner/hotels/1/offers -H 'X-User-Id: 3'
curl -X POST localhost:8080/api/v1/partner/hotels/1/offers/1/respond -H 'X-User-Id: 3' -H 'Content-Type: application/json' \
  -d '{"action":"COUNTER","counterPrice":1500,"round":1}'

# 3. Guest checks offers, then accepts one (rooms are held for 10 minutes)
curl localhost:8080/api/v1/bids/1 -H 'X-User-Id: 1'
curl -X POST localhost:8080/api/v1/bids/1/offers/2/accept -H 'X-User-Id: 1'

# 4. Dev-only fake payment, then the hotel name and address are revealed
curl -X POST localhost:8080/api/v1/dev/bookings/1/confirm-payment -H 'X-User-Id: 1' -H 'Content-Type: application/json' -d '{"paymentRef":"pay_123"}'
curl localhost:8080/api/v1/bookings/1 -H 'X-User-Id: 1'
```
(Raise a bid: `POST /api/v1/bids/{id}/raise` with `{"pricePerNight":1250}`. Max 3 rounds.)

## Three portals, one backend

| Portal | Client | Backend routes |
|---|---|---|
| Guest | `bidstay-app` (React Native) | `/api/v1/hotels`, `/bids`, `/bookings` |
| Hotelier | `bidstay-portals` (web) at `/hotel` | `/api/v1/partner/**` (hotel access checked per route) |
| Admin | `bidstay-portals` (web) at `/admin` | `/api/v1/admin/**` (ADMIN role only) |

`GET /api/v1/me` returns the caller's role. Seed users (dev profile): 1,2 guests; 3 owner of hotels 1-2; 4 admin; 5 owner of the two pending hotels.

New behaviour: hotels choose how bids are answered (`auto_mode`, `auto_counter` + %, `auto_decline`; manual mode always waits for the owner).
Everything the platform tells a hotel, and every reply, is stored in `partner_messages` (the WhatsApp-style chat). `PartnerService.handleChatText` accepts commands with an offer and round, such as `YES 42/1` and `COUNTER 1500 42/1`.
Hotels need four verified documents before an admin can approve them. `InventoryJobs` keeps 90 days of nights published for live hotels.

## Design decisions worth knowing

| Rule | Where |
|---|---|
| A hotel's minimum price lives only in `room_types.min_price` and is never in a guest DTO | `BidDtos`, `RoomTypeRepository.findSummaries` |
| Bid >= minimum is auto-accepted; one `evaluate()` method is used on create and on raise | `BidService.evaluate` |
| Rooms are never double sold: `SELECT ... FOR UPDATE` ordered by date, plus a DB `CHECK (held+sold <= total)` | `InventoryService`, `V1__core_schema.sql` |
| Guest picks an offer: rooms held 10 min, other offers cancelled, booking `PENDING_PAYMENT` | `BidService.acceptOffer` |
| Payment confirmation is idempotent (webhooks repeat) | `BookingService.confirmPayment` |
| Hotel reply vs expiry job cannot both win: `@Version` on offers | `BidOffer`, `GlobalExceptionHandler` |
| Timers live in the DB and are polled every 30s, so they survive restarts | `ExpiryJobs` |
| Hotel name/address hidden until `CONFIRMED` | `BookingService.toView` |
| Flyway owns the schema; Hibernate only validates | `application.yml` |

## NOT done yet (next steps, in order)

1. **Staff auth**: add property-scoped invitations for hotel staff and MFA for admins.
2. **Razorpay**: create order on accept, verify webhook signature, call `confirmPayment`; refund if payment arrives after hold expiry.
3. **Notifications**: FCM push to guests; WhatsApp Business API gateway for `PartnerChatService.bot` and an inbound webhook calling `PartnerService.handleChatText` (the chat and command parsing already exist).
4. **Realtime**: WebSocket/SSE so the guest app sees offers arrive; polling `GET /bids/{id}` works meanwhile.
5. **Real document upload** (S3) instead of a number/link, and automatic GST lookup.
7. **Bid sanity**: price guide per area and a floor (for example 30% of listed price) to block junk bids.
8. **Cancellation and refunds, reviews, ID verification, hourly stays** (feature-flagged).
9. Before running 2+ instances: ShedLock for `ExpiryJobs`, Redis for rate limiting.
