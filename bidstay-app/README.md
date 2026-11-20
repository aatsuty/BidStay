# BidStay app (React Native + Expo)

One TypeScript codebase for **Android, iOS and Web**. Expo SDK 58, Expo Router (file-based navigation), TanStack Query (server state, polling).

## Run it

```bash
npm install
cp .env.example .env        # set EXPO_PUBLIC_API_URL if needed
npx expo start              # press a (Android), i (iOS), w (web) or scan the QR with Expo Go
```
Start the backend first (`docker compose up -d` then `mvn spring-boot:run -Dspring-boot.run.profiles=dev`).

| Where the app runs | Backend URL to use (Settings tab) |
|---|---|
| Web / iOS simulator | `http://localhost:8080` |
| Android emulator | `http://10.0.2.2:8080` |
| Real phone | `http://<your computer's LAN IP>:8080` (same Wi-Fi) |

Checks: `npm run typecheck`. Production web build: `npx expo export --platform web`.

## Screens

| Route | What it does |
|---|---|
| `(tabs)/index` | Search: city, check-in, nights, rooms, guests, room type |
| `hotels` | Pick hotels (or Select all), see price guide, name your price, send the bid |
| `bid/[id]` | Live offers (polls every 4s), per-hotel countdown, Book, raise bid (max 3 rounds) |
| `booking/[id]` | Payment hold countdown, pay, confirmation (hotel address revealed only after payment) |
| `(tabs)/trips` | All your bids and bookings |
| `(tabs)/profile` | **Dev only:** backend URL and guest user ID used in place of login |

## Try the whole flow on one device

1. Settings: guest `1`.
2. Search -> Find hotels -> Select all -> bid `1100` -> Send. Hotel Metro Inn (hidden minimum 1000) accepts instantly; Hotel Sunrise (minimum 1200) waits.
3. Open the **hotelier portal** (bidstay-portals) as the hotel owner and counter with `1500`, or just reply `COUNTER 1500` in its WhatsApp-style chat.
4. Back in **My Trips**, open the bid: a counter offer appears. Book it, then Pay.

## Code map

- `src/lib/api.ts`: fetch client, typed errors (reads Spring `ProblemDetail.detail`), 15s timeout
- `src/lib/types.ts`: mirrors the backend DTOs. Keep in sync with `BidDtos.java`
- `src/lib/session.tsx`: **auth placeholder** (user/hotel ID headers). Replace with a JWT after OTP login
- `src/components/ui.tsx`: Button, Card, Chip, Field, Stepper, status badges

## Not done yet

1. OTP login + JWT (backend first), then remove the Settings/Hotel dev tabs from guest builds.
2. Razorpay checkout. The "Pay" button is dev-only (`__DEV__` or `EXPO_PUBLIC_DEV_PAYMENTS=1`); production shows "coming soon".
3. Push notifications (`expo-notifications` + FCM) instead of polling; WebSocket later.
4. Map / "near me" search, filters, hotel photos and detail page (needs backend support).
5. Cancellation, reviews, ID verification, hourly stays.
6. Real app icon/splash, EAS build profiles (`eas build`), Play Store / App Store setup.
