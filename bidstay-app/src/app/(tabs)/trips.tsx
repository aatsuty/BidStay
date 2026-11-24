import React, { useCallback } from 'react';
import { FlatList, Pressable, RefreshControl, Text, View } from 'react-native';
import { router, useFocusEffect } from 'expo-router';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { BidBadge, BookingBadge, Button, Card, Empty, ErrorText, Loading } from '@/components/ui';
import { errorMessage, useApi } from '@/lib/api';
import { formatDate, indiaDateTime, inr } from '@/lib/format';
import { colors } from '@/lib/theme';
import { useSession } from '@/lib/session';
import type { BidView, DirectTrip } from '@/lib/types';

export default function TripsScreen() {
  const api = useApi();
  const { cacheKey } = useSession();
  const q = useQuery({ queryKey: ['bids', cacheKey], queryFn: () => api.get<BidView[]>('/api/v1/bids') });
  const direct = useInfiniteQuery({
    queryKey: ['direct-trips', cacheKey], initialPageParam: 0,
    queryFn: ({ pageParam }) => api.get<DirectTrip[]>('/api/v1/bookings/direct' + (pageParam ? '?before=' + pageParam : '')),
    getNextPageParam: (page) => page.length === 50 ? page.at(-1)?.bookingId : undefined,
  });
  const directTrips = direct.data?.pages.flat() ?? [];

  // Refresh whenever this tab comes into view.
  useFocusEffect(
    useCallback(() => {
      q.refetch();
      direct.refetch();
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [cacheKey]),
  );

  if (q.isLoading || direct.isLoading) return <Loading />;

  return (
    <FlatList
      data={q.data ?? []}
      keyExtractor={(b) => String(b.bidId)}
      contentContainerStyle={{ padding: 16, flexGrow: 1 }}
      refreshControl={<RefreshControl refreshing={q.isRefetching || direct.isRefetching} onRefresh={() => { void q.refetch(); void direct.refetch(); }} />}
      ListHeaderComponent={<>
        <ErrorText message={q.error || direct.error ? errorMessage(q.error ?? direct.error) : null} />
        {directTrips.map((booking) => <Pressable key={booking.bookingId} onPress={() => router.push(`/booking/${booking.bookingId}`)}>
          <Card>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', gap: 8 }}>
              <Text style={{ flex: 1, fontSize: 16, fontWeight: '700', color: colors.text }}>{booking.hotelName}</Text>
              <BookingBadge status={booking.status} />
            </View>
            <Text style={{ color: colors.muted, marginTop: 6 }}>{booking.shortStay
              ? `${indiaDateTime(booking.shortStay.arrival)} · ${booking.shortStay.hours} hours`
              : `${formatDate(booking.checkIn)} → ${formatDate(booking.checkOut)}`} · {inr(booking.totalAmount)}</Text>
          </Card>
        </Pressable>)}
        {direct.hasNextPage && <Button title="Load more bookings" variant="ghost" loading={direct.isFetchingNextPage}
          onPress={() => { void direct.fetchNextPage(); }} />}
      </>}
      ListEmptyComponent={q.error || direct.error || directTrips.length ? undefined : <Empty title="No trips yet" hint="Find hotels and book a stay." />}
      renderItem={({ item: b }) => {
        const ready = b.offers.filter((o) => o.status === 'ACCEPTED' || o.status === 'COUNTERED').length;
        const go = () => (b.bookingId ? router.push(`/booking/${b.bookingId}`) : router.push(`/bid/${b.bidId}`));
        return (
          <Pressable onPress={go}>
            <Card>
              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text style={{ fontSize: 16, fontWeight: '700', color: colors.text }}>
                  {formatDate(b.checkIn)} → {formatDate(b.checkOut)}
                </Text>
                <BidBadge status={b.status} />
              </View>
              <Text style={{ color: colors.muted, marginTop: 6 }}>
                Your bid {inr(b.pricePerNight)} / night · {b.rooms} room{b.rooms > 1 ? 's' : ''}
              </Text>
              {b.status === 'OPEN' && (
                <Text style={{ color: colors.primaryDark, marginTop: 4, fontWeight: '600' }}>
                  {ready} offer{ready === 1 ? '' : 's'} ready · round {b.round}/{b.maxRounds}
                </Text>
              )}
            </Card>
          </Pressable>
        );
      }}
    />
  );
}
