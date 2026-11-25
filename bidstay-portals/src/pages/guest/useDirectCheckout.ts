import { useRef } from 'react';
import { useApi } from '../../lib/api';
import { useSession } from '../../lib/session';
import type { Booking } from './models';

export function useDirectCheckout() {
  const api = useApi();
  const { cacheKey } = useSession();
  const attempt = useRef<{ fingerprint: string; requestId: Promise<string> } | null>(null);

  return async (kind: 'listed' | 'hourly', stay: Record<string, number | string>) => {
    const fingerprint = JSON.stringify([cacheKey, kind, stay]);
    if (attempt.current?.fingerprint !== fingerprint) {
      const requestId = api.post<{ requestId: string }>('/api/v1/bookings/request-id').then((response) => response.requestId);
      attempt.current = { fingerprint, requestId };
      void requestId.catch(() => {
        if (attempt.current?.requestId === requestId) attempt.current = null;
      });
    }
    return api.post<Booking>('/api/v1/bookings/' + kind, { ...stay, requestId: await attempt.current.requestId });
  };
}
