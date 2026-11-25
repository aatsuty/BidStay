# BidStay for Android and iOS

The Expo app opens the responsive BidStay portal from `bidstay-portals`. Guests, hotel partners, and admins use the same screens, business rules, and Spring Boot API on the website and inside the app. Android and iOS provide device actions for location, printing receipts, copying invitations, and sharing downloaded documents.

## Run on the same Wi-Fi

1. Start PostgreSQL and Spring Boot in `bidstay-backend` as described in the [pilot setup](../docs/pilot.md). Use the `dev` profile for demo accounts.
2. In `bidstay-portals`, run these commands and keep the terminal open.

   ```bash
   npm ci
   npm run dev -- --host 0.0.0.0 --port 5173 --strictPort
   ```

3. In `bidstay-app`, run these commands in a separate terminal and scan the QR with Expo Go for SDK 58. Use a trusted Wi-Fi network for local demo access. [Expo Go downloads](https://expo.dev/go?sdkVersion=58)

   ```bash
   npm ci
   npx expo start --go --lan
   ```

The app uses Metro's Wi-Fi host and port 5173 for the portal. The portal forwards `/api` to the backend on the computer, at `127.0.0.1:8080` by default. Set `VITE_API_PROXY_TARGET` in the portal environment when Spring Boot uses a different address. Open `http://localhost:5173/hourly` on the computer to use the same hourly search.

For a different portal host or port, put `EXPO_PUBLIC_PORTAL_URL` in `bidstay-app/.env.local` and restart Expo. For example, `EXPO_PUBLIC_PORTAL_URL=http://192.168.1.50:5173`. Use the actual Wi-Fi address printed by Vite. The previous Expo app used a separate backend setting. After upgrading, sign in through the shared portal.

### Android says it failed to download the remote update

Open the Expo terminal's address with `/status` on the phone, such as `http://192.168.1.50:8081/status`. When it responds with `packager-status:running`, run `npx expo start --go --lan --clear` and scan the new QR. Also open the Vite network address with `/hourly` on the phone. A timeout points to Wi-Fi reachability or the computer's Node.js firewall permissions. An Expo tunnel forwards Metro only and requires a separately reachable portal URL.

## Release configuration

Set `EXPO_PUBLIC_PORTAL_URL` to the HTTPS origin of the deployed portal when building Android and iOS. Serve React Router paths through the portal's index page and proxy `/api` to Spring Boot. Browser users open the deployed portal directly. An Expo web build redirects to the same portal.

Run `npm run typecheck`, `npx expo lint`, and `npx expo export --platform android --platform ios` before creating store builds. Test OTP, Razorpay checkout, uploads, document sharing, receipt printing, and location on a physical Android and iOS device before release.
