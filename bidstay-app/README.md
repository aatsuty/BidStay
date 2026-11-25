# BidStay app (React Native + Expo)

One TypeScript codebase for **Android, iOS and Web**. Expo SDK 58, Expo Router (file-based navigation), TanStack Query (server state, polling).

## Run it

```bash
npm ci
npx expo start --go --lan
```

Scan the QR with the build of Expo Go that matches **SDK 58**. For Android, [download it from Expo](https://expo.dev/go?sdkVersion=58&platform=android&device=true). In the terminal, press `a` for an Android emulator, `i` for iOS Simulator, or `w` for web.

### Android says it failed to download the remote update

1. Connect the phone and the computer running Metro to the same Wi-Fi. Stop the existing Expo process, then run `npx expo start --go --lan --clear` in **bidstay-app**. Scan the new QR.
2. On the phone, open `http://<computer-Wi-Fi-IP>:8081/status` in Chrome, using the IP and port printed by Expo. Expect `packager-status:running`. A timeout points to LAN access. Check the computer's firewall permission for Node.js and whether the Wi-Fi allows devices to reach one another.
3. If the LAN connection is blocked, run `npx expo start --go --tunnel --clear` and scan its QR. Expo CLI may prompt for `@expo/ngrok`. If Metro is reachable but the download still fails, check the first error in the Expo terminal after scanning. In a managed shell, an error creating the `.expo` directory requires a writable Expo state directory.

### Connect the backend after the app opens

Run `docker compose up -d` and `mvn spring-boot:run -Dspring-boot.run.profiles=dev` from **bidstay-backend**. An Expo tunnel forwards Metro traffic. Spring Boot on port 8080 must be reachable separately.

| Where the app runs | Backend URL to use |
|---|---|
| Web / iOS simulator | `http://localhost:8080` |
| Android emulator | `http://10.0.2.2:8080` |
| Real phone | `http://<your computer's LAN IP>:8080` (same Wi-Fi) |

On the phone, test `http://<computer-Wi-Fi-IP>:8080/actuator/health`. When it loads, enter the backend URL under **Profile → Local demo settings** and save it. You can also set `EXPO_PUBLIC_API_URL` in your local `.env` before starting Expo. An already saved demo URL can override that default, so check the value under Profile.

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

1. In **Profile → Local demo settings**, use guest `1`.
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
