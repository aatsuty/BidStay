import { useRef } from 'react';
import { router } from 'expo-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError, useApi } from './api';
import { useSession } from './session';
import type { BookingView } from './types';

type Guests = { rooms: number; guests: number; roomTypeId: number };
type ListedStay = Guests & { checkIn: string; checkOut: string; listedRate: number };
type HourlyStay = Guests & { date: string; arrival: string; hours: number; ratePerRoom: number };

export function useDirectCheckout() {
  const attempt = useRef<{ fingerprint: string; requestId: string } | null>(null);
  const api = useApi();
  const { auth, cacheKey } = useSession();
  const client = useQueryClient();
  const checkout = useMutation({
    mutationFn: async (stay: ListedStay | HourlyStay) => {
      const fingerprint = JSON.stringify([cacheKey, stay]);
      if (attempt.current?.fingerprint !== fingerprint) {
        const { requestId } = await api.post<{ requestId: string }>('/api/v1/bookings/request-id');
        attempt.current = { fingerprint, requestId };
      }
      const kind = 'date' in stay ? 'hourly' : 'listed';
      return api.post<BookingView>('/api/v1/bookings/' + kind, { ...stay, requestId: attempt.current.requestId });
    },
    onSuccess: (booking) => {
      void client.invalidateQueries({ queryKey: ['direct-trips', cacheKey] });
      router.replace(`/booking/${booking.bookingId}`);
    },
  });
  const signInRequired = (!__DEV__ && !auth) || (checkout.error instanceof ApiError && checkout.error.status === 401);
  return {
    error: checkout.error, isPending: checkout.isPending, signInRequired,
    begin: (stay: ListedStay | HourlyStay) => {
      if (signInRequired) router.push('/(tabs)/profile');
      else checkout.mutate(stay);
    },
  };
}
