import React, { useCallback } from 'react';
import { FlatList, Pressable, RefreshControl, Text, View } from 'react-native';
import { router, useFocusEffect } from 'expo-router';
import { useQuery } from '@tanstack/react-query';
import { BidBadge, Card, Empty, ErrorText, Loading } from '@/components/ui';
import { errorMessage, useApi } from '@/lib/api';
import { formatDate, inr } from '@/lib/format';
import { colors } from '@/lib/theme';
import type { BidView } from '@/lib/types';

export default function TripsScreen() {
  const api = useApi();
  const q = useQuery({ queryKey: ['bids'], queryFn: () => api.get<BidView[]>('/api/v1/bids') });

  // Refresh whenever this tab comes into view.
  useFocusEffect(
    useCallback(() => {
      q.refetch();
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []),
  );

  if (q.isLoading) return <Loading />;

  return (
    <FlatList
      data={q.data ?? []}
      keyExtractor={(b) => String(b.bidId)}
      contentContainerStyle={{ padding: 16, flexGrow: 1 }}
      refreshControl={<RefreshControl refreshing={q.isRefetching} onRefresh={() => { void q.refetch(); }} />}
      ListHeaderComponent={<ErrorText message={q.error ? errorMessage(q.error) : null} />}
      ListEmptyComponent={q.error ? undefined : <Empty title="No trips yet" hint="Search for hotels and place your first bid." />}
      renderItem={({ item: b }) => {
        const ready = b.offers.filter((o) => o.status === 'ACCEPTED' || o.status === 'COUNTERED').length;
        const go = () => (b.bookingId && (b.status === 'HELD' || b.status === 'BOOKED') ? router.push(`/booking/${b.bookingId}`) : router.push(`/bid/${b.bidId}`));
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
