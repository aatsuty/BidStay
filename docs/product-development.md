# BidStay product and system development

## Build trust into the booking flow

The guest should be able to see the actual property and room, understand the stay total and cancellation terms, then find the booking, receipt, refund or support history in one place. The partner should see the same dates, price and booking state. Define the finance and customer-service workflows before opening public checkout.

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

Move existing confirmed stays and unexpired holds into the shared allocation model before enabling hourly sales for a room pool. Exercise these cases with actual concurrent transactions before launch:

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
