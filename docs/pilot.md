# BidStay pilot setup

Use the existing Spring Boot API and PostgreSQL database with the React web portal and the Expo guest app. The web portal serves the guest, hotel partner, and admin experiences from the same deployment. See the [product and scaling roadmap](product-development.md) for listing galleries, alerts and hourly stays.

## Run locally

1. Start PostgreSQL and run the backend with `mvn spring-boot:run -Dspring-boot.run.profiles=dev` in `bidstay-backend`. Flyway upgrades an existing seeded development database in place.
2. Run `npm ci` followed by `npm run dev` in `bidstay-portals`. Open the printed web URL. **Find a stay** is the guest experience at `/`. **Partner portal** opens staff or admin sign-in with local demo accounts available in development.
3. In a separate terminal, run `npm ci` and `npx expo start` in `bidstay-app` for Android and iOS. The native app uses the same availability and checkout APIs.
4. To test bidding, choose an upcoming Delhi stay, select Hotel Sunrise Paharganj, and send a ₹1,100 bid as the demo guest. Use the demo owner account to counter at ₹1,500. Return to the guest offer and complete demo checkout. Open **My trips** to verify the booking.

The web deployment can serve its API under the same HTTPS origin. Set `VITE_API_URL` or `EXPO_PUBLIC_API_URL` when clients need an explicit API origin. The development server uses OpenStreetMap tiles. Configure a production tile service with `VITE_MAP_TILE_URL` and `VITE_MAP_ATTRIBUTION` before routing pilot map traffic. The search shows approximate hotel locations. The hero photograph illustrates the stay concept.

## Enable providers

Set credentials in the backend environment or secret manager.

| Capability | Configuration |
|---|---|
| Guest and hotel staff OTP | Configure Twilio Verify for six-digit SMS codes and the India destination. Set `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, and `TWILIO_VERIFY_SERVICE_SID`. Use the provided provider fraud controls and per-client API gateway limits before enabling public SMS requests. |
| Staff invitations | Hotel managers use **Profile & documents → Staff access** to issue or revoke 48-hour staff invitations and remove access. Admins use **Hotels → Manage staff access** to assign or remove managers as well. Invitees complete OTP on the phone number in the invitation. Use a staff phone distinct from an existing guest or admin account. |
| Admin sign-in | Provision an `ADMIN` user in the database. Set `BIDSTAY_ADMIN_PHONE` to that user's 10-digit number, `BIDSTAY_ADMIN_PASSWORD_HASH` to their BCrypt password hash, and `BIDSTAY_ADMIN_TOTP_SECRET` to the matching Base32 secret already enrolled in their authenticator. This configuration supports one pre-provisioned administrator. |
| Private document uploads | Set `BIDSTAY_DOCUMENT_BUCKET` and `AWS_REGION` for a private S3 bucket. Grant the backend role access to the `hotel-documents/` and `listing-media/` prefixes. Development can store files locally under `.bidstay-documents`. Uploads create immutable versions and reviews record the reviewer and decision. Replacing an approved document pauses the listing for review. |
| Razorpay | Set `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, and a `RAZORPAY_REFERENCE_PREFIX` unique to each deployment sharing the Razorpay account (1–19 letters, digits or hyphens). Register `POST /api/v1/webhooks/razorpay` for `payment_link.paid`, `refund.processed`, and `refund.failed`. Start with Razorpay test mode. |
| Cancellation | Pending checkouts can be cancelled. For a free-cancellation pilot, set `BIDSTAY_FREE_CANCELLATION=true` to quote the cutoff on new bookings. The cutoff is noon India time one day before check-in. Eligible paid cancellations release inventory and queue a refund. Previously created bookings retain their stored policy. |
| WhatsApp | Set `WHATSAPP_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_APP_SECRET`, `WHATSAPP_VERIFY_TOKEN`, and the approved template names listed below. Subscribe the app to messages and use `/api/v1/webhooks/whatsapp` for verification and signed events. Staff opt in from the inbox after phone verification. Guests opt in from **My trips**. |
| AI reply suggestions | Set `OPENAI_API_KEY` and optionally `WHATSAPP_AI_MODEL`. The assistant receives the text of a hotel reply and proposes a command. A staff member must send the explicit command before the bid changes. |

Create approved WhatsApp templates with the configured `WHATSAPP_TEMPLATE_LANGUAGE`. The placeholders must match this order.

| Template variable | Body placeholders |
|---|---|
| `WHATSAPP_HOTEL_BID_TEMPLATE` | Request reference, stay dates, room count, offer price per room per night |
| `WHATSAPP_GUEST_OFFER_TEMPLATE` | Bid ID, offer price per room per night |
| `WHATSAPP_BOOKING_TEMPLATE` | Booking ID |

Give the hotel template instructions for quoting the original message or appending its request reference. For example, a staff member can send `COUNTER 1500 42/1`. Quotes are tied to the receiving phone, hotel, offer, and bid round. Delivery statuses in the partner inbox come from Meta.

## Security operations

Use the `dev` profile only on local test systems. The public instance needs HTTPS and protected backend credentials.

- **Sign-in limits.** The API shares PostgreSQL counters across replicas. Per client, each 10-minute window defaults to 20 OTP sends, 60 OTP verification requests and 20 admin sign-in requests. Set `BIDSTAY_OTP_SEND_LIMIT`, `BIDSTAY_OTP_VERIFY_LIMIT` or `BIDSTAY_ADMIN_SIGN_IN_LIMIT` to adjust. The existing phone cooldown and admin account attempt limits also apply. The API uses the connection address and groups IPv6 under a /64. For a trusted ingress, restrict direct access to the app, strip incoming forwarding headers at the edge and configure Tomcat's trusted proxy handling before enabling `BIDSTAY_FORWARD_HEADERS_STRATEGY=native`. Keep gateway and SMS provider abuse limits for a public pilot.
- **Hotel membership.** Regular members can work on their assigned property. Managers manage staff invitations and membership. Admins can assign managers and recover a hotel whose managers have all left. Revocation removes the property's membership, disables WhatsApp consent for it, and revokes all sessions if the person has no remaining hotel memberships. Review legacy memberships after the upgrade. Existing invited members are classified as staff, while associations that were provisioned directly keep manager access.
- **Audit events.** The admin-only **Audit log** shows new staff invitations and removals, hotel and document decisions, photo publication, admin sign-ins, booking cancellations and refund status changes. It stores actor and record identifiers without OTP codes, passwords, invitation links or payment credentials. Audit history starts with this migration. Use restricted database roles and external log retention for security monitoring at launch.

## Data and scaling

- PostgreSQL is authoritative for bids, room nights, booking holds, payments, and consent. Checkout locks the relevant room nights, and the database prevents two active bookings for one bid.
- Signed Razorpay events confirm bookings. Duplicate payment IDs are ignored. A capture arriving after the hold deadline is recorded and queued for a refund.
- WhatsApp notifications are written to an outbox with the business action. Workers can claim queued rows across instances. Delivery events and inbound message IDs are deduplicated. Allow for a repeated outgoing message if a provider accepts it and the network drops its response.
- Search filters dated capacity and uses cursor paging. The admin verification list is paged. Inventory generation uses idempotent batches.
- Measure API latency, database wait time, late payment rate, and message queue age before increasing bid fan-out. At 100 bids per second, a cap of 50 hotels allows up to 5,000 evaluations per second.
- Before scaling past the pilot, add load and recovery testing, backup restore drills, deployment monitoring, and a shared rate limiter. Evaluate incremental live updates if polling becomes a significant load. Provider account limits, payment settlements, and production tax rules need validation with pilot partners.
