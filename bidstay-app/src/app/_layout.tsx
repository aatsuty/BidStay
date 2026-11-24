import React from 'react';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SessionProvider } from '@/lib/session';
import { colors } from '@/lib/theme';

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, staleTime: 2_000 } },
});

export default function RootLayout() {
  return (
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <StatusBar style="dark" />
        <Stack
          screenOptions={{
            headerTintColor: colors.primaryDark,
            headerTitleStyle: { color: colors.text },
            contentStyle: { backgroundColor: colors.bg },
          }}
        >
          <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
          <Stack.Screen name="hotels" options={{ title: 'Choose hotels & bid' }} />
          <Stack.Screen name="hourly" options={{ title: 'Hourly stays' }} />
          <Stack.Screen name="bid/[id]" options={{ title: 'Your bid' }} />
          <Stack.Screen name="booking/[id]" options={{ title: 'Booking' }} />
        </Stack>
      </SessionProvider>
    </QueryClientProvider>
  );
}
