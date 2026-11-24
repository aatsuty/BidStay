import React, { useMemo, useState } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, Text, View } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Card, Chip, Empty, ErrorText, Field, Loading } from '@/components/ui';
import { ApiError, errorMessage, useApi } from '@/lib/api';
import { useSession } from '@/lib/session';
import { formatDate, inr, nightsBetween } from '@/lib/format';
import { colors, radius } from '@/lib/theme';
import type { BidView, CreateBidInput, HotelSummary, RoomCategory } from '@/lib/types';

const NO_HOTELS: HotelSummary[] = [];

export default function HotelsScreen() {
  const p = useLocalSearchParams<{
    city: string;
    checkIn: string;
    checkOut: string;
    rooms: string;
    guests: string;
    category: RoomCategory;
  }>();
  const api = useApi();
  const { auth, baseUrl, cacheKey } = useSession();
  const qc = useQueryClient();
  const nights = nightsBetween(p.checkIn, p.checkOut);

  const hotelsQuery = useQuery({
    queryKey: ['hotels', p.city, p.category, baseUrl],
    queryFn: () => api.get<HotelSummary[]>(`/api/v1/hotels?city=${encodeURIComponent(p.city)}&category=${p.category}`),
  });

  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [price, setPrice] = useState('');

  const hotels = hotelsQuery.data ?? NO_HOTELS;
  const chosen = hotels.filter((h) => selected.has(h.id));

  // Price guide: average listed price of the hotels in play. (Later: real market data from past bids.)
  const guide = useMemo(() => {
    const pool = chosen.length ? chosen : hotels;
    if (!pool.length) return 0;
    return Math.round(pool.reduce((s, h) => s + h.listedPrice, 0) / pool.length);
  }, [chosen, hotels]);

  const numericPrice = Number(price);
  const priceOk = Number.isFinite(numericPrice) && numericPrice > 0;

  const placeBid = useMutation({
    mutationKey: ['place-bid', cacheKey],
    mutationFn: (body: CreateBidInput) => api.post<BidView>('/api/v1/bids', body),
    onSuccess: (bid) => {
      qc.invalidateQueries({ queryKey: ['bids'] });
      router.replace(`/bid/${bid.bidId}`);
    },
  });

  const signInRequired = (!__DEV__ && !auth)
    || (placeBid.error instanceof ApiError && placeBid.error.status === 401);

  function toggle(id: number) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function submit() {
    placeBid.mutate({
      city: p.city,
      checkIn: p.checkIn,
      checkOut: p.checkOut,
      rooms: Number(p.rooms),
      guests: Number(p.guests),
      category: p.category,
      pricePerNight: numericPrice,
      hotelIds: chosen.map((h) => h.id),
    });
  }

  return (
    <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView contentContainerStyle={{ padding: 16, paddingBottom: 24 }} keyboardShouldPersistTaps="handled">
        <Text style={{ color: colors.muted, marginBottom: 12 }}>
          {p.city} · {formatDate(p.checkIn)} → {formatDate(p.checkOut)} · {nights} night{nights > 1 ? 's' : ''} · {p.rooms} room
          {Number(p.rooms) > 1 ? 's' : ''}, {p.guests} guests
        </Text>

        {hotelsQuery.isLoading && <Loading />}
        <ErrorText message={hotelsQuery.error ? errorMessage(hotelsQuery.error) : null} />
        {!hotelsQuery.isLoading && !hotelsQuery.error && hotels.length === 0 && (
          <Empty title="No hotels found" hint="Try another city or room type." />
        )}

        {hotels.length > 0 && (
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
            <Text style={{ fontWeight: '700', color: colors.text }}>
              {chosen.length} of {hotels.length} selected
            </Text>
            <Pressable onPress={() => setSelected(chosen.length === hotels.length ? new Set() : new Set(hotels.map((h) => h.id)))}>
              <Text style={{ color: colors.primary, fontWeight: '700' }}>{chosen.length === hotels.length ? 'Clear all' : 'Select all'}</Text>
            </Pressable>
          </View>
        )}

        {hotels.map((h) => {
          const on = selected.has(h.id);
          return (
            <Pressable key={h.id} onPress={() => toggle(h.id)}>
              <Card style={on ? { borderColor: colors.primary, borderWidth: 2 } : undefined}>
                <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                  <View
                    style={{
                      width: 24,
                      height: 24,
                      borderRadius: 6,
                      borderWidth: 2,
                      borderColor: on ? colors.primary : colors.border,
                      backgroundColor: on ? colors.primary : '#fff',
                      alignItems: 'center',
                      justifyContent: 'center',
                      marginRight: 12,
                    }}
                  >
                    {on && <Text style={{ color: '#fff', fontWeight: '800' }}>✓</Text>}
                  </View>
                  <View style={{ flex: 1 }}>
                    <Text style={{ fontSize: 16, fontWeight: '700', color: colors.text }}>{h.name}</Text>
                    <Text style={{ color: colors.muted, marginTop: 2 }}>
                      {h.area}
                      {h.starRating ? ` · ${'★'.repeat(h.starRating)}` : ''}
                    </Text>
                  </View>
                  <View style={{ alignItems: 'flex-end' }}>
                    <Text style={{ color: colors.muted, fontSize: 12 }}>Listed</Text>
                    <Text style={{ fontWeight: '700', color: colors.text }}>{inr(h.listedPrice)}</Text>
                  </View>
                </View>
              </Card>
            </Pressable>
          );
        })}
      </ScrollView>

      {hotels.length > 0 && (
        <View
          style={{
            padding: 16,
            backgroundColor: '#fff',
            borderTopWidth: 1,
            borderTopColor: colors.border,
            borderTopLeftRadius: radius.lg,
            borderTopRightRadius: radius.lg,
          }}
        >
          <ErrorText message={placeBid.error ? errorMessage(placeBid.error) : null} />
          <Field
            label={`Your price per night${guide ? ` (avg listed ${inr(guide)})` : ''}`}
            value={price}
            onChangeText={(t) => setPrice(t.replace(/[^0-9.]/g, ''))}
            keyboardType="numeric"
            placeholder="e.g. 1200"
          />
          {guide > 0 && (
            <View style={{ flexDirection: 'row', flexWrap: 'wrap' }}>
              {[0.6, 0.7, 0.8, 0.9].map((f) => (
                <Chip key={f} label={`${Math.round((1 - f) * 100)}% off · ${inr(guide * f)}`} selected={Math.round(guide * f) === numericPrice} onPress={() => setPrice(String(Math.round(guide * f)))} />
              ))}
            </View>
          )}
          <Button
            title={signInRequired ? 'Sign in to bid' : chosen.length ? `Send bid to ${chosen.length} hotel${chosen.length > 1 ? 's' : ''}` : 'Select at least one hotel'}
            onPress={signInRequired ? () => router.push({ pathname: '/(tabs)/profile', params: { from: 'bid' } }) : submit}
            disabled={!signInRequired && (!chosen.length || !priceOk)}
            loading={placeBid.isPending}
          />
        </View>
      )}
    </KeyboardAvoidingView>
  );
}
