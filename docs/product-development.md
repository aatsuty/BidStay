# BidStay product and system development

## HTML template comparison

The supplied `BidStay.html` was run in Chromium on desktop and at a 390-pixel mobile viewport. Eight browser scenarios exercised all 53 named click actions, including hotel switching, WhatsApp replies, hourly checkout, wallet, registration, approval, rejection and the demo reset. Its payments, ID verification, messages and approvals change in-memory sample data. Reloading the page clears the changes.

| Journey in the template | Current BidStay behaviour | Remaining work |
|---|---|---|
| Find hotels by area, dates, guests, rooms and room type. Choose on the map or in a list. | Overnight web search checks dated PostgreSQL inventory and applies neighbourhood, minimum stars, maximum listed rate and an optional 5 km location filter before paging. | Map-pin search and structured amenity and property-policy filters. |
| Adjust a rate with buttons or a slider and see predicted acceptance. | Web shows the listed-rate average and the full proposed stay total. Any acceptance comes from a stored hotel reply or authorised rule. | Add price guidance only after there is enough real cohort data. Keep individual hotels' floors private. |
| Browse photos, room categories, verified badges, policies, amenities and reviews. | Approved property and room photos, published stay policies and moderated reviews from completed paid bookings appear in the web gallery. | Structured amenities. |
| Compare replies, see the lowest quote, raise the bid or cancel the request. | Web shows ready and pending counts, lowest comparable stay totals, and rate increments. The web and Expo app can withdraw an open bid. A withdrawal closes live offers and leaves an audit entry. | The template can go back to competing offers after choosing a hotel. BidStay currently closes those offers when it holds rooms for checkout. |
| Save a search, toggle alerts, see replies and booking updates. | Guests can save, pause, resume and delete a search. Recorded rate drops and inventory releases wake matching overnight searches by destination, room category and dates. | Observe queue latency with real pilot traffic. |
| Browse and book at the listed rate, or select an hourly stay and verify ID. | Web and Expo support listed-price and 3- and 6-hour checkout. Hourly and overnight bookings share a PostgreSQL capacity check in enabled room pools. | Agree on the real hourly ID and eligibility workflow with participating hotels. |
| Simulate UPI, card or pay-at-hotel, cashback, a refundable ₹100 bid deposit and a refund. | BidStay charges at checkout using Razorpay. Checkout records price and policy, and guests can reopen the receipt and see refund status. | Verify enabled Razorpay payment methods and a refund on a physical phone. Pay-at-hotel, wallet, cashback and a bid deposit are separate product decisions. |
| Partner handles requests, sets rates and inventory, and reads demand insights. | Property-scoped staff can accept, counter and decline. The portal supports automatic rules, dated availability and paid bookings. Admins can open the neighbourhood metrics dashboard. | Date-specific deals and a decision on sponsored placements. |
| Partner sends a simulated WhatsApp command or taps a quick reply. | The local portal inbox accepts an exact, round-specific command. The Meta integration uses opt-in, signed events and delivery status. AI suggestions require explicit staff confirmation. | Verify sending, delivery, replies and opt-outs with real test accounts. |
| Register a property, review documents, approve or reject, and review KPIs. | Registration opens the new property's document checklist. Admins verify actual file versions, approve or reject, and review bids, bookings, commission and audit records. | Run a pilot with a real property, verified account access, approved media and settlement reconciliation. |

Local browser checks covered partner photo uploads through admin approval and guest viewing, saved alert matching, offer comparison and withdrawal, partner counteroffers, mobile checkout and receipts, and registration through public listing publication. PostgreSQL integration tests covered concurrent withdrawal and acceptance. Razorpay and WhatsApp provider behaviour was exercised with signed fixture events and test doubles. The comparison used the responsive web portal. Expo build checks covered native source changes, with device tests still pending.

## Build trust into the booking flow

Checkout shows the room type, nightly rate, room and night counts, exact total, cancellation deadline and any arrival policy the manager published. The booking keeps the terms shown when the guest chose the offer. After payment, guests can reopen their receipt and refund status from **My trips**. Verify the complete flow with a real property and agree on finance and customer-support workflows before opening public checkout.

Listing photos have their own review status. Hotel and room images are uploaded in the partner portal under **Listing photos** and moderated in **Admin → Listing photo review**. Approval publishes the images for an active hotel. If an existing upload is an approved JPEG or PNG under **Profile & documents**, an admin can choose **Publish to guest gallery** for that version. This creates a separate listing image. The original evidence remains in private document storage. Production storage needs access to both `hotel-documents/` and `listing-media/` prefixes.

Guests can save dates, a room type, a neighbourhood and a maximum nightly rate. **Alerts** shows public listed-rate matches with dated availability, offers, booking confirmations and cancellations. Matching on public listed rates preserves hoteliers’ private bid floors. PostgreSQL records rate drops and newly available inventory. Bounded workers wake affected searches, and periodic scans cover initial matches and delayed work.

## What makes the marketplace stronger

| Invest in | Why it matters | Measure |
|---|---|---|
| Concentrate supply in one neighbourhood and recruit hotels that can update availability reliably | A bidding marketplace works when a guest receives several useful replies in the same search area. | Hotels available per search, offer coverage, median first useful reply and paid conversion. |
| Give hoteliers rules to fill distressed inventory | A property can set private floors and sell same-day or off-peak stock without changing public rates. | Incremental occupied room-hours, realized net rate, and oversell and cancellation rates. |
| Recommend the best hotels to bid to | Cap fan-out and rank by inventory freshness, likely relevance and historical response quality. Use the price the guest entered. Publish price guidance only for cohorts with enough observations to protect individual hotels’ floors. | Useful offers per hotel contacted, reply time, quiet-hours opt-outs and conversion. |
| Give guests a clear fallback | Offer instant listed-price booking where inventory is reliable, and show useful alternatives when a bid goes unanswered. | Recovery after no offer, price-transparency support tickets and repeat bookings. |
| Connect reliable sources of inventory | Import via channel managers or hotel property-management systems with a last-sync timestamp and an explicit stale-feed state. | Sync lag, availability mismatch, reversal rates and share of hotels with a healthy feed. |

## Hourly stays

Managers opt in individual room types after saving the hotel's overnight stay policy. The pilot sells 3- or 6-hour stays on half-hour starts between 08:00 and 22:00 IST, up to 30 days ahead. Checkout stores the selected times, exact package price, arrival instructions and cancellation deadline.

Hourly and overnight stays in the pilot share dated room-pool capacity. Checkout locks the room type and measures peak overlap over the booking interval plus a 30-minute turnover buffer. The database counts both payment holds and confirmed purchases. Capacity edits use the same lock. Older overnight bookings with missing check-in times conservatively block through the checkout date. Existing room-night counters continue to serve nightly-only pools.

Integration tests exercise the last-room race, the turnover boundary, capacity reductions and cancellation. The pilot needs a front-desk process for early arrivals, extensions and ID checks. **Admin → Neighbourhood pilot** reports useful offers per bid, median time to first offer, paid bid conversion and booked room-hours where actual booking times are on file. Incremental room-hours require an occupancy baseline from participating hotels.

## Scaling the existing stack

- **Core writes.** Keep the Spring Boot modular monolith and PostgreSQL for bids, holds, bookings and money. Define stable booking and payment event IDs, review the search index with real query plans, and isolate worker throughput from API throughput. Stateless API instances can scale independently from messaging and refund workers.
- **Photos.** The public derivative endpoint supports an optional CDN origin and bounded shared-cache TTL. Configure CDN invalidation on withdrawal and keep verification evidence on the private access path. At higher upload volume, move resizing to a durable worker.
- **Search and alerts.** PostgreSQL serves cursor-paged dated availability and durable jobs for affected saved searches. At higher concurrency, cache public catalog metadata independently of live capacity and monitor the lag of the affected-search worker.
- **Operating targets.** Size the system by peak search requests, peak bids, hotels contacted per bid, concurrent checkout holds, photo bandwidth and provider queue age. For a first capacity exercise, model a burst of 100 bids per second against the existing 50-hotel maximum. That is up to 5,000 hotel evaluations per second before availability checks and notifications. Measure database lock waits, database CPU, search p95, late captures, duplicate bookings and oldest queued work. Record the load achieved and test restoring the database and private objects before claiming production readiness.

Pilot gate: complete a photo-backed booking on a phone with one real hotel, Razorpay Test Mode, live opt-in WhatsApp and refunds. Then expand to a neighbourhood cohort. Defer broad discounts, paid placement, wallets and loyalty until the core funnel and settlement workflow have real usage.
