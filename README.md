BidStay: Apna Rate, Apna Stay

Pilot branch setup, web routes, provider configuration and scaling notes are in [the BidStay pilot guide](docs/pilot.md).

A hotel booking platform where the guest names the price. One bid goes to many hotels at once. Each hotel can accept, counter or decline, and the booking is confirmed when both sides agree.

Status: pilot implementation available locally. The React web app serves all three roles. The Expo app opens that same portal on Android and iOS. The dev profile includes demo accounts and payment.

Why BidStay
Guests today	Hotels today
The online price is fixed, and often higher than the front-desk price	A room empty tonight cannot be sold tomorrow
Fees and taxes change the amount at checkout	Big platforms charge about 15 to 25% commission
Last-minute rooms are the most expensive, even when empty	Owners guess prices, and public discounts hurt their image

BidStay lets guests bargain the way they already do at a hotel front desk, online and with many hotels at the same time. Hotels keep their discounts private and fill rooms that would stay empty. We earn a small commission (8 to 12%) on confirmed bookings.

How it works
Search: city, dates, rooms, guests.
Bid: pick hotels and name a price per night. A price guide helps keep bids realistic.
Hotels reply within 15 minutes: accept, counter, or decline. Bids above a hotel's private minimum price can be accepted automatically.
Raise or accept: the guest can raise the bid (up to 3 rounds) or book the best offer.
Pay and confirm: the rooms are held for 10 minutes while the guest pays. The hotel's address is shown only after payment.
Three roles, one backend
App	For	Tech	Folder
Guest app	Travellers	React portal with an Expo app wrapper	bidstay-portals (/)
Hotel portal	Hotel owners	React, TypeScript, Vite	bidstay-portals (/hotel)
Admin portal	BidStay team	Same web project	bidstay-portals (/admin)
Backend API	All of the above	Java 21, Spring Boot 3, PostgreSQL, Flyway	bidstay-backend
 Guest portal (React)     ──┐
 Hotel portal (React) ──────┼──►  Spring Boot API  ──►  PostgreSQL
 Admin portal (React) ──────┘      bidding engine       (Flyway migrations)
                                   room inventory + holds
                                   WhatsApp-style chat
Features
Guest app
Search, hotel selection (or select all), price guide, quick "% off" bids
Live offers with a reply countdown per hotel: accepted, counter offer, waiting, declined, sold out
Raise the bid (max 3 rounds), book an offer, 10-minute room hold, payment step
Hotel name and address revealed only after payment
Hotel portal
WhatsApp-style chat: reply using the offer and round shown in the message, for example YES 42/1, NO 42/1 or COUNTER 1500 42/1. Bid cards with Accept, Decline and Counter buttons stay in sync with the chat
Rates and rules: private minimum price per room, automatic acceptance at or above the minimum, auto-counter for bids slightly below it, auto-decline, or manual approval of every bid
Availability calendar (cannot drop below rooms already booked), paid bookings with payout, 30-day insights
Registration with four documents: GST, licence, owner ID, photos
Admin portal
Document-by-document hotel verification. Approve is locked until all four are verified. Reject with a reason, suspend or reinstate
Overview of live hotels, bids, bookings, booking value and commission
Tracks the three pilot targets from the business plan: 30% of bids get an offer, 60% of hotel replies within 15 minutes, 15% of bids end in a paid booking
Latest bids and bookings
Backend
Bidding engine with automatic accept, counter and decline rules
No double booking: row-level locks plus a database constraint
Reply and payment-hold timers that live in the database, so they survive a restart
Idempotent payment confirmation (gateway webhooks repeat)
Role checks on every route: guest, hotel staff (own hotels only), admin
Everything said to a hotel and every reply is stored, ready to connect to real WhatsApp<img width="1440" height="900" alt="admin-1-overview-pilot-targets" src="https://github.com/user-attachments/assets/c0f5acaa-c2fa-4d54-9062-ec0e8b483802" />
<img width="1440" height="900" alt="admin-2-hotel-approval" src="https://github.com/user-attachments/assets/8b48f444-06ed-4550-9754-8da3a83dfce6" />
<img width="1440" height="900" alt="admin-3-all-documents-verified" src="https://github.com/user-attachments/assets/b57f6388-cb71-434a-83db-1be25f0125e2" />
<img width="1440" height="900" alt="admin-4-bids" src="https://github.com/user-attachments/assets/d1ba2579-437d-4e68-a418-8342a8f62bc6" />
<img width="1440" height="900" alt="admin-5-bookings-commission" src="https://github.com/user-attachments/assets/21af7195-24fc-4214-8429-cfdf266902e7" />
<img width="780" height="1600" alt="guest-1-search" src="https://github.com/user-attachments/assets/c0ac1d6e-57e9-4156-83fb-9237f0ecc041" />
<img width="780" height="1600" alt="guest-2-pick-hotels-and-bid" src="https://github.com/user-attachments/assets/c09313ff-67c0-443c-8af8-aa191250080d" />
<img width="780" height="1600" alt="guest-3-live-offers" src="https://github.com/user-attachments/assets/8d283a88-db37-4fee-9e83-151046b2f89b" />
<img width="780" height="1600" alt="guest-4-pay" src="https://github.com/user-attachments/assets/5c128344-56b0-4cc2-a06c-df153534a1f0" />
<img width="780" height="1600" alt="guest-5-booking-confirmed" src="https://github.com/user-attachments/assets/5b71cc50-3151-43b3-b9f9-c4328e622b71" />
<img width="780" height="1600" alt="guest-6-my-trips" src="https://github.com/user-attachments/assets/ef0d69f5-58e0-4cd8-bcfb-234eecbfbb83" />
<img width="1440" height="900" alt="hotel-1-requests-whatsapp-chat" src="https://github.com/user-attachments/assets/bbf4cd36-429d-4acb-901f-c17f098ed143" />
<img width="1440" height="900" alt="hotel-3-rates-and-rules" src="https://github.com/user-attachments/assets/93db7d56-f5d0-49d9-b9a1-32533f38ee90" />
<img width="1440" height="900" alt="hotel-2-after-whatsapp-reply" src="https://github.com/user-attachments/assets/1b9a2288-6d7d-44a3-9a72-790998f86e08" />
<img width="1440" height="900" alt="hotel-4-availability" src="https://github.com/user-attachments/assets/f07014d1-b572-491b-b1ff-1cffdbc4129f" />
<img width="1440" height="900" alt="hotel-5-bookings" src="https://github.com/user-attachments/assets/e32523d5-89de-43e6-a61a-1ab2004d8eaf" />
<img width="1440" height="900" alt="hotel-6-insights" src="https://github.com/user-attachments/assets/6782287c-658a-4c2b-9575-f85bec310325" />
<img width="1440" height="900" alt="hotel-7-verification-documents" src="https://github.com/user-attachments/assets/b2efa156-0811-4a2d-b008-0edad8a204c3" />

Hotel portal: bid requests and WhatsApp-style chat Hotel portal: rates and rules Admin portal: pilot targets Admin portal: hotel approval

More are in docs/screenshots.

Quick start

Needs Docker, Java 21, Maven and Node 22.13 or newer. See the [pilot setup](docs/pilot.md) for configuration.

```bash
# Start BidStay from the repository root
cd bidstay-backend
docker compose up -d
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Open [BidStay](http://localhost:8080/) and choose **Hourly stays** on the homepage. Maven builds the portal and Spring Boot serves it with the API.

```bash
# Optional second terminal for Android and iOS
cd bidstay-app
npm ci && npx expo start --go --lan --clear
```

[Expo Go and Wi-Fi setup](bidstay-app/README.md)

Demo accounts (the dev profile only): the portals' login page has buttons for the hotel owner (user 3), a new hotel owner with hotels waiting for approval (user 5), and the admin (user 4). Choose **Sign in → Continue with demo guest** to use the guest demo account.

Try it in 5 minutes: bid ₹1,100 at Hotel Sunrise and Hotel Metro Inn from the guest app. Metro Inn accepts instantly (private minimum ₹1,000). Sunrise (minimum ₹1,200) waits, so open the hotel portal and use the Counter button or copy the counter command from the chat message. Back in the guest app, book the counter offer and pay.

Project structure
bidstay/
  bidstay-backend/    Java Spring Boot API
    src/main/java/com/bidstay/
      bidding/        bid engine: auto-accept, counter, rounds, expiry
      booking/        bookings, payment hold, commission
      inventory/      rooms per night, double-booking protection
      partner/        hotel portal API, WhatsApp-style chat and commands
      admin/          approvals, stats, monitoring
      user/           roles and access checks
      hotel/          hotels, rooms, documents
    src/main/resources/db/migration   database schema (Flyway)
    src/main/resources/db/seed        demo data (dev profile only)
  bidstay-app/        Expo wrapper for the shared portal
  bidstay-portals/    Shared React portal for all three roles (Vite)
  docs/               setup guide, screenshots, business idea
Key design decisions
The hotel's minimum price is private. It never appears in any guest-facing response.
One place decides how a bid is answered (BidService.evaluate), used both when a bid is created and when it is raised, so the rules cannot drift apart.
Hotels choose how automatic they are: automatic mode, auto-counter within 5 to 20% below the minimum, auto-decline, or fully manual.
Flyway owns the database schema. Hibernate only validates it.
Access control is in one service (AccessService). Outside development, callers use verified guest, hotel staff, or admin sessions.
Pilot rollout

The build includes phone sign-in, property staff invitations, admin MFA, private document uploads,
Razorpay checkout and refunds, and WhatsApp messaging with optional AI reply drafts. See the
[pilot guide](docs/pilot.md) for configuration and local testing.
Pending pilot work includes provider onboarding, HTTPS deployment, backups, monitoring, and validating
tax and hotel settlement operations. Start with 20 to 30 independent Delhi NCR hotels. Hourly stays,
public reviews, cashback and featured placement are outside this overnight pilot.

Business model

8 to 12% commission on each confirmed booking (10% in the prototype), below the 15 to 25% of big platforms. Featured listings for hotels after the first six months. Full plan: docs/BidStay-Business-Idea.pdf.

Team

Abhinandan Sharma, Dibyanshoo Pandey, Prateek Gulati.
