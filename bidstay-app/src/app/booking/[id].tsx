import React, { useState } from 'react';
import { Linking, ScrollView, Text, View } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { BookingBadge, Button, Card, ErrorText, Loading } from '@/components/ui';
import { errorMessage, useApi } from '@/lib/api';
import { countdown, formatDate, indiaDateTime, nightsBetween, useNow } from '@/lib/format';
import { colors } from '@/lib/theme';
import { useSession } from '@/lib/session';
import type { BookingView } from '@/lib/types';

// Fake payment button: on in dev, and in internal test builds with EXPO_PUBLIC_DEV_PAYMENTS=1. Never in production.
const DEV_PAYMENTS = __DEV__ || process.env.EXPO_PUBLIC_DEV_PAYMENTS === '1';
const bookingAmount = new Intl.NumberFormat('en-IN', {
  style: 'currency', currency: 'INR', minimumFractionDigits: 0, maximumFractionDigits: 2,
});
const bookingTime = new Intl.DateTimeFormat('en-IN', {
  dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Kolkata',
});
const refundLabels: Record<string, string> = {
  REFUND_PENDING: 'Refund queued', REFUND_SUBMITTED: 'Refund processing',
  REFUND_PROCESSED: 'Refund processed', REFUND_REVIEW: 'Refund needs review',
};

export default function BookingScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const api = useApi();
  const { cacheKey } = useSession();
  const qc = useQueryClient();
  const now = useNow();
  const [confirmCancel, setConfirmCancel] = useState(false);
  const bookingKey = ['booking', id, cacheKey];

  const q = useQuery({
    queryKey: bookingKey,
    queryFn: () => api.get<BookingView>(`/api/v1/bookings/${id}`),
    refetchInterval: (query) => {
      const booking = query.state.data;
      return booking?.status === 'PENDING_PAYMENT' || booking?.refundStatus === 'REFUND_PENDING'
        || booking?.refundStatus === 'REFUND_SUBMITTED' ? 5000 : false;
    },
  });
  const b = q.data;

  const options = useQuery({ queryKey: ['payment-options', cacheKey], queryFn: () => api.get<{ razorpay: boolean }>('/api/v1/payments/options') });
  const checkout = useMutation({
    mutationFn: async () => {
      const payment = await api.post<{ url: string }>(`/api/v1/bookings/${id}/payment-link`);
      await Linking.openURL(payment.url);
    },
  });

  // DEV ONLY. Real Razorpay payments are confirmed by the backend webhook.
  const updateBooking = (data: BookingView) => {
    qc.setQueryData(bookingKey, data);
    void qc.invalidateQueries({ queryKey: ['bids'] });
    void qc.invalidateQueries({ queryKey: ['direct-trips', cacheKey] });
  };
  const pay = useMutation({
    mutationFn: () => api.post<BookingView>(`/api/v1/dev/bookings/${id}/confirm-payment`, { paymentRef: `dev_${Date.now()}` }),
    onSuccess: updateBooking,
  });
  const cancel = useMutation({
    mutationFn: () => api.post<BookingView>(`/api/v1/bookings/${id}/cancel`),
    onSuccess: (data) => {
      updateBooking(data);
      setConfirmCancel(false);
    },
  });

  if (q.isLoading) return <Loading />;
  if (!b) return <ErrorText message={errorMessage(q.error)} />;

  const nights = b.nights ?? nightsBetween(b.checkIn, b.checkOut);
  const left = b.status === 'PENDING_PAYMENT' ? countdown(b.payBy, now) : null;
  const cancelUntil = b.freeCancelUntil ? new Date(b.freeCancelUntil) : null;
  const isFreeCancellation = !!cancelUntil && cancelUntil.getTime() > now;
  const active = b.status === 'PENDING_PAYMENT' || b.status === 'CONFIRMED';
  const canCancel = b.status === 'PENDING_PAYMENT' || (b.status === 'CONFIRMED' && isFreeCancellation);

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
        {b.roomName && <Row k="Room type" v={b.roomName} />}
        <Row k="Check-in" v={b.shortStay ? indiaDateTime(b.shortStay.arrival)
          : formatDate(b.checkIn) + (b.stayPolicy ? ` from ${b.stayPolicy.checkInFrom} IST` : '')} />
        <Row k="Check-out" v={b.shortStay ? indiaDateTime(b.shortStay.departure)
          : formatDate(b.checkOut) + (b.stayPolicy ? ` by ${b.stayPolicy.checkOutBy} IST` : '')} />
        <Row k="Stay" v={`${b.shortStay ? `${b.shortStay.hours} hours` : `${nights} night${nights > 1 ? 's' : ''}`} · ${b.rooms} room${b.rooms > 1 ? 's' : ''}`} />
        <Row k={b.shortStay ? 'Rate per room for this slot' : 'Rate per room per night'}
          v={bookingAmount.format(b.shortStay?.ratePerRoom ?? b.pricePerNight ?? 0)} />
        <View style={{ height: 1, backgroundColor: colors.border, marginVertical: 12 }} />
        <Row k={b.receipt ? 'Original payment' : 'Total'} v={bookingAmount.format(b.totalAmount)} bold />
        {b.stayPolicy?.guestInstructions && <Text style={{ color: colors.text, marginTop: 10 }}>{b.stayPolicy.guestInstructions}</Text>}
        {active && <Text style={{ color: colors.muted, marginTop: 10 }}>
          {isFreeCancellation ? `Free cancellation until ${bookingTime.format(cancelUntil)} IST.`
            : cancelUntil ? `Free cancellation ended on ${bookingTime.format(cancelUntil)} IST.` : 'Free cancellation is unavailable after payment.'}
        </Text>}
      </Card>

      {b.receipt && <Card>
        <Text style={{ fontSize: 16, fontWeight: '800', color: colors.text }}>{b.receipt.demo ? 'Demo payment receipt' : 'Payment receipt'}</Text>
        <Row k="Payment reference" v={b.receipt.reference} />
        {b.receipt.recordedAt && <Row k="Payment recorded" v={bookingTime.format(new Date(b.receipt.recordedAt)) + ' IST'} />}
        {b.receipt.refundReference && <Row k="Refund reference" v={b.receipt.refundReference} />}
      </Card>}
      {b.refundStatus && <Text accessibilityLiveRegion="polite" style={{ color: colors.text, marginVertical: 8 }}>
        {refundLabels[b.refundStatus] ?? 'Refund status unavailable'}
      </Text>}

      {b.status === 'PENDING_PAYMENT' && (
        <>
          <Text style={{ textAlign: 'center', color: left ? colors.warn : colors.danger, fontWeight: '700', marginBottom: 12 }}>
            {left ? `Rooms held for you: ${left}` : 'The room hold has ended.'}
          </Text>
          <ErrorText message={checkout.error || pay.error ? errorMessage(checkout.error ?? pay.error) : null} />
          {left && (options.data?.razorpay ? (
            <Button title={`Pay ${bookingAmount.format(b.totalAmount)} with Razorpay`} onPress={() => checkout.mutate()} loading={checkout.isPending} />
          ) : DEV_PAYMENTS ? (
            <Button title="Complete demo payment" onPress={() => pay.mutate()} loading={pay.isPending} />
          ) : (
            <Text style={{ textAlign: 'center', color: colors.muted }}>Online payment is currently unavailable.</Text>
          ))}
        </>
      )}

      {canCancel && <View style={{ marginTop: 12, gap: 8 }}>
        <ErrorText message={cancel.error ? errorMessage(cancel.error) : null} />
        {confirmCancel ? <>
          <Text style={{ color: colors.text }}>{b.status === 'PENDING_PAYMENT' ? 'Cancel checkout and release your rooms?' : 'Cancel your stay and request a refund?'}</Text>
          <Button title="Confirm cancellation" variant="ghost" onPress={() => cancel.mutate()} loading={cancel.isPending} />
          <Button title="Keep booking" variant="ghost" onPress={() => setConfirmCancel(false)} disabled={cancel.isPending} />
        </> : <Button title={b.status === 'PENDING_PAYMENT' ? 'Cancel checkout' : 'Cancel booking'} variant="ghost" onPress={() => setConfirmCancel(true)} />}
      </View>}

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
    <View style={{ flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 3, gap: 8 }}>
      <Text style={{ color: colors.muted }}>{k}</Text>
      <Text style={{ color: colors.text, flexShrink: 1, textAlign: 'right', fontWeight: bold ? '800' : '600', fontSize: bold ? 18 : 14 }}>{v}</Text>
    </View>
  );
}
