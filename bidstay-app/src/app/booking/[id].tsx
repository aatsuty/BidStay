import React from 'react';
import { ScrollView, Text, View } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { BookingBadge, Button, Card, ErrorText, Loading } from '@/components/ui';
import { errorMessage, useApi } from '@/lib/api';
import { countdown, formatDate, inr, nightsBetween, useNow } from '@/lib/format';
import { colors } from '@/lib/theme';
import type { BookingView } from '@/lib/types';

// Fake payment button: on in dev, and in internal test builds with EXPO_PUBLIC_DEV_PAYMENTS=1. Never in production.
const DEV_PAYMENTS = __DEV__ || process.env.EXPO_PUBLIC_DEV_PAYMENTS === '1';

export default function BookingScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const api = useApi();
  const qc = useQueryClient();
  const now = useNow();

  const q = useQuery({
    queryKey: ['booking', id],
    queryFn: () => api.get<BookingView>(`/api/v1/bookings/${id}`),
    refetchInterval: (query) => (query.state.data?.status === 'PENDING_PAYMENT' ? 5000 : false),
  });
  const b = q.data;

  // DEV ONLY. Replaced by Razorpay checkout; the backend confirms from the payment webhook, not from the app.
  const pay = useMutation({
    mutationFn: () => api.post<BookingView>(`/api/v1/dev/bookings/${id}/confirm-payment`, { paymentRef: `dev_${Date.now()}` }),
    onSuccess: (data) => {
      qc.setQueryData(['booking', id], data);
      qc.invalidateQueries({ queryKey: ['bids'] });
    },
  });

  if (q.isLoading) return <Loading />;
  if (!b) return <ErrorText message={errorMessage(q.error)} />;

  const nights = nightsBetween(b.checkIn, b.checkOut);
  const left = b.status === 'PENDING_PAYMENT' ? countdown(b.payBy, now) : null;

  return (
    <ScrollView contentContainerStyle={{ padding: 16 }}>
      {b.status === 'CONFIRMED' && (
        <View style={{ alignItems: 'center', marginVertical: 16 }}>
          <Text style={{ fontSize: 48 }}>🎉</Text>
          <Text style={{ fontSize: 22, fontWeight: '800', color: colors.success, marginTop: 4 }}>Booking confirmed</Text>
        </View>
      )}

      <Card>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
          <Text style={{ fontSize: 14, color: colors.muted }}>Booking #{b.bookingId}</Text>
          <BookingBadge status={b.status} />
        </View>

        {b.hotelName ? (
          <>
            <Text style={{ fontSize: 20, fontWeight: '800', color: colors.text, marginTop: 8 }}>{b.hotelName}</Text>
            <Text style={{ color: colors.muted, marginTop: 2 }}>{b.hotelAddress}</Text>
          </>
        ) : (
          <Text style={{ color: colors.muted, marginTop: 8 }}>Hotel name and address are shown once payment is complete.</Text>
        )}

        <View style={{ height: 1, backgroundColor: colors.border, marginVertical: 12 }} />
        <Row k="Check-in" v={formatDate(b.checkIn)} />
        <Row k="Check-out" v={formatDate(b.checkOut)} />
        <Row k="Stay" v={`${nights} night${nights > 1 ? 's' : ''} · ${b.rooms} room${b.rooms > 1 ? 's' : ''}`} />
        <Row k="Your price" v={`${inr(b.pricePerNight)} / night`} />
        <View style={{ height: 1, backgroundColor: colors.border, marginVertical: 12 }} />
        <Row k="Total" v={inr(b.totalAmount)} bold />
      </Card>

      {b.status === 'PENDING_PAYMENT' && (
        <>
          <Text style={{ textAlign: 'center', color: left ? colors.warn : colors.danger, fontWeight: '700', marginBottom: 12 }}>
            {left ? `Rooms held for you: ${left}` : 'Hold time is over. Pay now or the rooms may be released.'}
          </Text>
          <ErrorText message={pay.error ? errorMessage(pay.error) : null} />
          {DEV_PAYMENTS ? (
            <Button title={`Pay ${inr(b.totalAmount)} (dev mode, no real payment)`} onPress={() => pay.mutate()} loading={pay.isPending} />
          ) : (
            <Text style={{ textAlign: 'center', color: colors.muted }}>Online payment is coming soon.</Text>
          )}
        </>
      )}

      {b.status === 'EXPIRED' && (
        <Card style={{ backgroundColor: colors.graySoft }}>
          <Text style={{ color: colors.text }}>The payment window closed and the rooms were released. You can start a new bid anytime.</Text>
        </Card>
      )}

      <Button title="Back to home" variant="ghost" onPress={() => router.replace('/')} style={{ marginTop: 12 }} />
    </ScrollView>
  );
}

function Row({ k, v, bold }: { k: string; v: string; bold?: boolean }) {
  return (
    <View style={{ flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 3 }}>
      <Text style={{ color: colors.muted }}>{k}</Text>
      <Text style={{ color: colors.text, fontWeight: bold ? '800' : '600', fontSize: bold ? 18 : 14 }}>{v}</Text>
    </View>
  );
}
