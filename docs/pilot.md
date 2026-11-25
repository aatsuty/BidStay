# BidStay pilot setup

Spring Boot and PostgreSQL serve one React portal for guests, hotel partners, and admins. The Expo app opens the same portal on Android and iOS. See the [product and scaling roadmap](product-development.md) for listing galleries, alerts and hourly stays.

## Run locally

1. Start PostgreSQL and run the backend with `mvn spring-boot:run -Dspring-boot.run.profiles=dev` in `bidstay-backend`. Flyway upgrades an existing seeded development database in place.
2. Run `npm ci` followed by `npm run dev -- --host 0.0.0.0 --port 5173 --strictPort` in `bidstay-portals`. Open the printed web URL. **Find a stay** is the guest experience at `/`. **Partner portal** opens staff or admin sign-in with local demo accounts available in development.
3. In a separate terminal, run `npm ci` and `npx expo start --go --lan` in `bidstay-app`. Use Expo Go for SDK 58 on the same Wi-Fi. The phone opens the Vite portal and Vite forwards API requests to Spring Boot. [Mobile setup and download error troubleshooting](../bidstay-app/README.md#android-says-it-failed-to-download-the-remote-update)
4. To test bidding, choose an upcoming Delhi stay, select Hotel Sunrise Paharganj, and send a ₹1,100 bid as the demo guest. Use the demo owner account to counter at ₹1,500. In **Profile & documents → Stay policy**, set check-in, check-out and arrival requirements. Return to the guest offer, review the terms and complete demo checkout. Open **My trips** to verify the booking and demo receipt.

For a release, serve the portal and API under the same HTTPS origin and set `EXPO_PUBLIC_PORTAL_URL` for the app build. In development, `VITE_API_PROXY_TARGET` overrides the default backend target of `http://127.0.0.1:8080`. The development server uses OpenStreetMap tiles. Configure a production tile service with `VITE_MAP_TILE_URL` and `VITE_MAP_ATTRIBUTION` before routing pilot map traffic. The search shows approximate hotel locations. The hero photograph illustrates the stay concept.

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

## Verify the first real stay

1. Confirm the hotel's room inventory, approved hotel and room photos, and check-in requirements with its manager. Enter the actual terms under **Profile & documents → Stay policy**. Existing checkouts keep the terms captured when the guest chose the offer.
2. In Razorpay Test Mode on a phone, choose the offer and compare the rate, room and night counts, and total with the hosted payment page. Complete payment and reopen **My trips**. Match the receipt reference and amount with Razorpay.
3. Cancel the booking within its displayed free-cancellation window. Check that availability returns, the guest keeps the original payment receipt, and the refund reference and final status agree with Razorpay.

The guest receipt records the payment and any recorded refund reference. Agree on tax invoice and settlement responsibilities with the hotel before accepting live money.

To test listed-price checkout, open a dated hotel result and choose **Book at listed price**. For hourly checkout, first save the overnight policy in **Profile & documents**, then publish 3- and 6-hour prices for the room under **Rates & rules → Hourly stays**. Select **Hourly** above the search form and choose an arrival time. Published 3- and 6-hour room rates also appear in the property gallery. Complete the booking and verify the slot in both **My trips** and the hotel’s **Bookings** screen.

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
- **Audit events.** The admin-only **Audit log** shows new staff invitations and removals, hotel and document decisions, stay policy edits, photo publication, admin sign-ins, booking cancellations and refund status changes. It stores actor and record identifiers without OTP codes, passwords, invitation links or payment credentials. Use restricted database roles and external log retention for security monitoring at launch.

## Data and scaling

- PostgreSQL is authoritative for bids, room nights, booking holds, payments, and consent. Checkout locks the relevant room nights, and the database prevents two active bookings for one bid.
- Signed Razorpay events confirm bookings. Duplicate payment IDs are ignored. A capture arriving after the hold deadline is recorded and queued for a refund.
- WhatsApp notifications are written to an outbox with the business action. Workers can claim queued rows across instances. Delivery events and inbound message IDs are deduplicated. Allow for a repeated outgoing message if a provider accepts it and the network drops its response.
- Search filters dated capacity and uses cursor paging. The admin verification list is paged. Inventory generation uses idempotent batches.
- Measure API latency, database wait time, late payment rate, and message queue age before increasing bid fan-out. At 100 bids per second, a cap of 50 hotels allows up to 5,000 evaluations per second.
- Provider account limits, payment settlements and production tax rules need validation with pilot partners. Use production-like staging to measure peak throughput and confirm recovery time against the pilot's targets.

### Local capacity and recovery checks

Run `BIDSTAY_LOAD_URL=http://localhost:8080 node scripts/pilot-load.mjs` from the repository root against the seeded development API with an isolated database. The harness uses demo guests, creates bids and sets a future room pool to one room. It verifies dated search, bid fan-out and that one checkout wins the last room. Enable hourly rates for the seeded Standard Room to include both booking formats in the race.

On the local seeded PostgreSQL run at concurrency 12, all 180 search requests and 48 bids to four hotels succeeded. Search p95 was 123 ms and bid p95 was 367 ms. Of 24 overlapping hourly and overnight checkout attempts, one held the last room. These measurements describe the local fixture with external providers disabled.

For a database restore drill, take a PostgreSQL custom-format dump with `pg_dump -Fc` and use `pg_restore --no-owner` against a freshly created scratch database. Compare booking, review, saved-alert and photo row counts and run availability checks against the restored copy. Back up and restore the media store separately. Verify that an approved listing renders and a restricted document still requires an authorised reviewer.

The local restore retained row counts, the confirmed hourly stay and its occupied slot, and the approved review. An approved thumbnail and a private verification file matched the originals byte for byte. A guest request for the private file returned 403.

### Public media edge cache

Set `VITE_PUBLIC_MEDIA_URL` and `EXPO_PUBLIC_MEDIA_URL` to the CDN origin. Route only `/api/v1/media/*` through that origin and configure it to honour the response TTL. Set `BIDSTAY_MEDIA_EDGE_TTL_SECONDS` on the API to opt into shared caching, capped at 300 seconds. Purge both the thumbnail and full image URL when a photo is withdrawn. Keep partner previews, admin previews and verification documents on the authenticated API origin.
