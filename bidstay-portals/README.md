# BidStay portals (hotelier + admin)

React 19 + TypeScript + Vite. One web app, two role-guarded portals. (The guest app is the separate React Native project.)

```bash
npm install
npm run dev            # http://localhost:5173  (set VITE_API_URL in .env if the backend is not on :8080)
npm run build          # static files in dist/
```
Start the backend with the `dev` profile first. Sign in with one of the seeded accounts on the login page.

| Account (seed) | Portal | What you see |
|---|---|---|
| Hotel owner (user 3) | `/hotel` | Live hotels Sunrise Paharganj and Metro Inn |
| New hotel owner (user 5) | `/hotel` | Delhi Grand Stay and Pahar Palace, waiting for approval |
| BidStay admin (user 4) | `/admin` | Approvals, bids, bookings, commission |

## Hotelier portal
- **Requests**: bid cards with a reply countdown and Accept, Decline and Counter actions. **Bid activity** shows replies, their source and WhatsApp delivery status.
- **Rates & rules**: listed and private minimum price per room, Automatic mode (accept at/above minimum), auto-counter (within 5-20% below the minimum), auto-decline, and a "try a guest bid" tester that shows what each rule would do.
- **Availability**: next 14 nights per room type; change rooms per night (cannot go below booked or held rooms).
- **Bookings**: paid bookings with guest contact (revealed only after payment), BidStay fee and payout.
- **Insights**: bids, accepts, counters, average reply time, payout (30 days).
- **Profile & documents**: submit GST, licence, owner ID, photos. A rejected hotel returns to review when a document is resubmitted.
- **Register**: a new owner creates a hotel with its room types.

## Admin portal
- **Overview**: live/pending hotels, bids, paid bookings, booking value, commission, and the three **pilot targets from the business plan** (30% of bids get an offer, 60% of hotel replies within 15 min, 15% of bids end in a paid booking), each with a progress bar and target marker.
- **Hotels**: document-by-document verification; **Approve is locked until all four are verified**; reject with a reason (sent to the owner's chat); suspend / reinstate.
- **Bids** and **Bookings & commission**: latest 100 each.

## Auth (placeholder)
Login picks a user ID and sends `X-User-Id`. The backend already enforces roles and hotel ownership on every route (`AccessService`), but anyone can claim any ID. Replace `src/lib/session.tsx` + the login page with phone + OTP and a JWT when the backend issues tokens.

## Real WhatsApp
Configure the Meta WhatsApp Cloud API and approved templates in the backend environment. Hoteliers can reply in WhatsApp using the linked request reference, such as `COUNTER 1500 42/1`. See the [pilot runbook](../docs/pilot.md).
