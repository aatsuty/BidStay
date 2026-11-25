# BidStay for Android and iOS

The Expo app opens the responsive BidStay portal from `bidstay-portals`. Guests, hotel partners, and admins use the same screens, business rules, and Spring Boot API on the website and inside the app. Android and iOS provide device actions for location, printing receipts, copying invitations, and sharing downloaded documents.

## Run on the same Wi-Fi

1. Start BidStay in `bidstay-backend` as described in the [pilot setup](../docs/pilot.md). Verify the homepage at [localhost:8080](http://localhost:8080/).
2. In `bidstay-app`, run these commands in a separate terminal and scan the QR with Expo Go for SDK 58. Use a trusted Wi-Fi network for local demo access. [Expo Go downloads](https://expo.dev/go?sdkVersion=58)

   ```bash
   npm ci
   npx expo start --go --lan --clear
   ```

The app uses Metro's Wi-Fi host and port 8080 for the portal. The browser and app both show **Hourly stays** on the homepage.

For a different portal host or port, set `EXPO_PUBLIC_PORTAL_URL` in `bidstay-app/.env.local` to the full portal origin and restart Expo. Remove an earlier port 5173 override to use the default port 8080.

### Android says it failed to download the remote update

Open the Expo terminal's address with `/status` on the phone, such as `http://192.168.1.50:8081/status`. When it responds with `packager-status:running`, run `npx expo start --go --lan --clear` and scan the new QR. Check that the homepage also loads in your phone's browser at your computer's Wi-Fi address on port 8080. An Expo tunnel forwards Metro only and requires a separately reachable portal URL.

## Release configuration

Deploy the Spring Boot JAR with the bundled portal behind HTTPS. Set `EXPO_PUBLIC_PORTAL_URL` to that HTTPS origin when building Android and iOS. An Expo web build redirects to the same portal.

Run `npm run typecheck`, `npx expo lint`, and `npx expo export --platform android --platform ios` before creating store builds. Test OTP, Razorpay checkout, uploads, document sharing, receipt printing, and location on a physical Android and iOS device before release.
