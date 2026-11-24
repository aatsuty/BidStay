# BidStay product and system development

## HTML template comparison

The supplied `BidStay.html` was run in Chromium on desktop and at a 390-pixel mobile viewport. Eight browser scenarios exercised all 53 named click actions, including hotel switching, WhatsApp replies, hourly checkout, wallet, registration, approval, rejection and the demo reset. Its payments, ID verification, messages and approvals change in-memory sample data. Reloading the page clears the changes.

| Journey in the template | Current BidStay behaviour | Remaining work |
|---|---|---|
| Find hotels by area, dates, guests, rooms and room type. Choose on the map or in a list. | Overnight web search checks dated PostgreSQL inventory, with an interactive map, neighbourhood selection and explicit recipients. | Device-location and map-pin radius search, star, amenity and property-policy filters. Current web neighbourhood options come from loaded results. |
| Adjust a rate with buttons or a slider and see predicted acceptance. | Web shows the listed-rate average and the full proposed stay total. Any acceptance comes from a stored hotel reply or authorised rule. | Add price guidance only after there is enough real cohort data. Keep individual hotels' floors private. |
| Browse photos, room categories, verified badges, policies, amenities and reviews. | Approved property and room uploads populate real galleries. Guests can preview them and published stay times from the list or an offer. | Structured amenities and reviews from completed bookings. |
| Compare replies, see the lowest quote, raise the bid or cancel the request. | Web shows ready and pending counts, lowest comparable stay totals, and rate increments. The web and Expo app can withdraw an open bid. A withdrawal closes live offers and leaves an audit entry. | The template can go back to competing offers after choosing a hotel. BidStay currently closes those offers when it holds rooms for checkout. |
| Save a search, toggle alerts, see replies and booking updates. | Guests can save, pause, resume and delete a search, open dated results, and see in-app offer, booking and listed-rate matches. WhatsApp trip updates use opt-in. | Match affected saved searches from inventory or price events. The scanner currently works in bounded batches. |
| Browse and book at the listed rate, or select an hourly stay and verify ID. | Guests can browse overnight inventory and select a bid offer for checkout. | Listed-price checkout, shared hourly and overnight inventory, and real hourly eligibility and ID checks. |
| Simulate UPI, card or pay-at-hotel, cashback, a refundable ₹100 bid deposit and a refund. | BidStay charges at checkout using Razorpay. Checkout records price and policy, and guests can reopen the receipt and see refund status. | Verify enabled Razorpay payment methods and a refund on a physical phone. Pay-at-hotel, wallet, cashback and a bid deposit are separate product decisions. |
| Partner handles requests, sets rates and inventory, and reads demand insights. | Property-scoped staff can accept, counter and decline. The portal supports automatic rules, dated availability, paid bookings and property metrics. | Date-specific deals, neighbourhood demand aggregates and a decision on sponsored placements. |
| Partner sends a simulated WhatsApp command or taps a quick reply. | The local portal inbox accepts an exact, round-specific command. The Meta integration uses opt-in, signed events and delivery status. AI suggestions require explicit staff confirmation. | Verify sending, delivery, replies and opt-outs with real test accounts. |
| Register a property, review documents, approve or reject, and review KPIs. | Registration opens the new property's document checklist. Admins verify actual file versions, approve or reject, and review bids, bookings, commission and audit records. | Run a pilot with a real property, verified account access, approved media and settlement reconciliation. |

Local browser checks covered partner photo uploads through admin approval and guest viewing, saved alert matching, offer comparison and withdrawal, partner counteroffers, mobile checkout and receipts, and registration through public listing publication. PostgreSQL integration tests covered concurrent withdrawal and acceptance. Razorpay and WhatsApp provider behaviour was exercised with signed fixture events and test doubles. The comparison used the responsive web portal. Expo build checks covered native source changes, with device tests still pending.

## Build trust into the booking flow

Checkout shows the room type, nightly rate, room and night counts, exact total, cancellation deadline and any arrival policy the manager published. The booking keeps the terms shown when the guest chose the offer. After payment, guests can reopen their receipt and refund status from **My trips**. Verify the complete flow with a real property and agree on finance and customer-support workflows before opening public checkout.

Listing photos have their own review status. Hotel and room images are uploaded in the partner portal under **Listing photos** and moderated in **Admin → Listing photo review**. Approval publishes the images for an active hotel. If an existing upload is an approved JPEG or PNG under **Profile & documents**, an admin can choose **Publish to guest gallery** for that version. This creates a separate listing image. The original evidence remains in private document storage. Production storage needs access to both `hotel-documents/` and `listing-media/` prefixes.

Guests can save dates, a room type, a neighbourhood and a maximum nightly rate. **Alerts** shows public listed-rate matches with dated availability, offers, booking confirmations and cancellations. Saving a search or receiving an alert does not hold rooms. Matching on public listed rates preserves hoteliers’ private bid floors. The pilot scanner checks bounded batches. A production matcher should react to actual rate and inventory changes for affected dates.

## What makes the marketplace stronger

| Invest in | Why it matters | Measure |
|---|---|---|
| Concentrate supply in one neighbourhood and recruit hotels that can update availability reliably | A bidding marketplace works when a guest receives several useful replies in the same search area. | Hotels available per search, offer coverage, median first useful reply and paid conversion. |
| Give hoteliers rules to fill distressed inventory | A property can set private floors and sell same-day or off-peak stock without changing public rates. | Incremental occupied room-hours, realized net rate, and oversell and cancellation rates. |
| Recommend the best hotels to bid to | Cap fan-out and rank by inventory freshness, likely relevance and historical response quality. Use the price the guest entered. Publish price guidance only for cohorts with enough observations to protect individual hotels’ floors. | Useful offers per hotel contacted, reply time, quiet-hours opt-outs and conversion. |
| Give guests a clear fallback | Offer instant listed-price booking where inventory is reliable, and show useful alternatives when a bid goes unanswered. | Recovery after no offer, price-transparency support tickets and repeat bookings. |
| Connect reliable sources of inventory | Import via channel managers or hotel property-management systems with a last-sync timestamp and an explicit stale-feed state. | Sync lag, availability mismatch, reversal rates and share of hotels with a healthy feed. |

## Hourly stays

Start with a small opt-in pilot, fixed durations such as 3 or 6 hours, exact arrival and departure times in each property's time zone, and a hotel-configurable turnover buffer. Expose guest and property policies before taking payment. Store the selected timing, pricing, taxes and cancellation rules as a booking snapshot.

Use a shared allocation model for hourly and overnight stays. Selling both formats from independent counters can double-book the same room. For hotels that track individually assigned rooms, PostgreSQL can reject overlaps per room using half-open timestamp ranges on active allocations. For hotels that manage pooled room types, publish capacity in 30-minute blocks for the hourly pilot horizon. Every booking or hold locks the affected resource blocks in order and consumes capacity in one transaction. Overnight bookings in those pools must reserve the blocks they overlap, including the turnover period. Avoid materializing hourly slots across the entire catalog. Start with opted-in room pools and a short rolling horizon.

Move existing confirmed stays and unexpired holds into the shared allocation model before enabling hourly sales for a room pool. Use the booking's saved check-in and check-out times to translate existing overnight stays. Resolve missing times with the pilot property before enabling its room pool. Exercise these cases with actual concurrent transactions before launch:

- An hourly checkout and an overnight checkout try to hold the last room.
- The guest arrives early or extends the stay into a later reservation.
- A payment arrives after the hold expired or a hotel lowers availability.
- The guest cancels and the correct blocks return to inventory.

Keep the current nightly inventory path for hotels outside that pilot until the new path is validated. Track incremental revenue and room-hours separately from overnight conversion.

## Scaling the existing stack

- **Core writes.** Keep the Spring Boot modular monolith and PostgreSQL for bids, holds, bookings and money. Define stable booking and payment event IDs, review the search index with real query plans, and isolate worker throughput from API throughput. Stateless API instances can scale independently from messaging and refund workers.
- **Photos.** Serve only approved derivatives. The current pilot generates resized JPEGs, stores data privately, and checks publication on image requests. At higher upload or download volume, move resizing to a worker and serve approved immutable derivatives through a CDN with invalidation when media is withdrawn. Keep verification evidence on a separate private access path.
- **Search and alerts.** Keep cursor paging and dated availability in PostgreSQL for the pilot. At higher concurrency, cache public catalog metadata separately from live availability. Use inventory and rate events to find affected saved searches by destination and dates. Keep the commit-time capacity check on the primary database.
- **Operating targets.** Size the system by peak search requests, peak bids, hotels contacted per bid, concurrent checkout holds, photo bandwidth and provider queue age. For a first capacity exercise, model a burst of 100 bids per second against the existing 50-hotel maximum. That is up to 5,000 hotel evaluations per second before availability checks and notifications. Measure database lock waits, database CPU, search p95, late captures, duplicate bookings and oldest queued work. Record the load achieved and test restoring the database and private objects before claiming production readiness.

Pilot gate: complete a photo-backed booking on a phone with one real hotel, Razorpay Test Mode, live opt-in WhatsApp and refunds. Then expand to a neighbourhood cohort. Defer broad discounts, paid placement, wallets and loyalty until the core funnel and settlement workflow have real usage.
