import React, { useState } from 'react';
import { ScrollView, Text, View } from 'react-native';
import { useInfiniteQuery } from '@tanstack/react-query';
import { Button, Card, Chip, Empty, ErrorText, Field, Loading, Stepper } from '@/components/ui';
import { errorMessage, useApi } from '@/lib/api';
import { useDirectCheckout } from '@/lib/direct-checkout';
import { addDays, formatDate, inr, nightsBetween, toISODate } from '@/lib/format';
import { useSession } from '@/lib/session';
import { colors } from '@/lib/theme';
import type { RoomCategory } from '@/lib/types';

type Criteria = { city: string; date: string; arrival: string; hours: number; category: RoomCategory; rooms: number; guests: number };
type Listing = { hotelName: string; area: string; roomTypeId: number; roomName: string; ratePerRoom: number };

export default function HourlyScreen() {
  const api = useApi();
  const { baseUrl } = useSession();
  const checkout = useDirectCheckout();
  const today = toISODate(new Date());
  const [draft, setDraft] = useState<Criteria>({ city: 'Delhi', date: addDays(today, 1),
    arrival: '12:00', hours: 3, category: 'STANDARD', rooms: 1, guests: 2 });
  const [criteria, setCriteria] = useState<Criteria>();
  const update = (patch: Partial<Criteria>) => setDraft((current) => ({ ...current, ...patch }));
  const results = useInfiniteQuery({
    queryKey: ['hourly-hotels', baseUrl, criteria], enabled: !!criteria, initialPageParam: 0,
    queryFn: ({ pageParam }) => {
      const query = new URLSearchParams(Object.entries({ ...criteria, after: pageParam })
        .map(([key, value]) => [key, String(value)]));
      return api.get<{ hotels: Listing[]; nextCursor?: number }>('/api/v1/hotels/hourly?' + query);
    },
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const hotels = results.data?.pages.flatMap((page) => page.hotels) ?? [];
  const validTime = /^(0[89]|1\d):(00|30)$/.test(draft.arrival)
    && draft.arrival <= (draft.hours === 3 ? '19:00' : '16:00');

  return <ScrollView contentContainerStyle={{ padding: 16 }} keyboardShouldPersistTaps="handled">
    <Card>
      <Field label="Destination" value={draft.city} onChangeText={(city) => update({ city })} />
      <Stepper label="Date" value={formatDate(draft.date)} onDec={() => update({ date: addDays(draft.date, -1) })}
        onInc={() => update({ date: addDays(draft.date, 1) })} decDisabled={draft.date <= today} incDisabled={nightsBetween(today, draft.date) >= 30} />
      <View style={{ flexDirection: 'row', flexWrap: 'wrap' }}>
        {[3, 6].map((hours) => <Chip key={hours} label={`${hours} hours`} selected={draft.hours === hours} onPress={() => update({ hours })} />)}
      </View>
      <Field label="Arrival time (24-hour IST)" value={draft.arrival} onChangeText={(arrival) => update({ arrival })} placeholder="12:00" />
      <Stepper label="Rooms" value={String(draft.rooms)} onDec={() => update({ rooms: draft.rooms - 1 })}
        onInc={() => update({ rooms: draft.rooms + 1 })} decDisabled={draft.rooms <= 1} incDisabled={draft.rooms >= 10} />
      <Stepper label="Guests" value={String(draft.guests)} onDec={() => update({ guests: draft.guests - 1 })}
        onInc={() => update({ guests: draft.guests + 1 })} decDisabled={draft.guests <= 1} incDisabled={draft.guests >= 40} />
      <View style={{ flexDirection: 'row', flexWrap: 'wrap' }}>
        {(['STANDARD', 'DELUXE', 'SUITE'] as const).map((category) => <Chip key={category}
          label={category[0] + category.slice(1).toLowerCase()} selected={draft.category === category} onPress={() => update({ category })} />)}
      </View>
      <Button title="Find hourly stays" disabled={!draft.city.trim() || !validTime || checkout.isPending} onPress={() => {
        const next = { ...draft, city: draft.city.trim() };
        if (JSON.stringify(next) === JSON.stringify(criteria)) void results.refetch();
        setCriteria(next);
      }} />
    </Card>
    <ErrorText message={results.error || checkout.error ? errorMessage(results.error ?? checkout.error) : null} />
    {criteria && <>
      <Text style={{ marginBottom: 10, color: colors.muted }}>{formatDate(criteria.date)} · {criteria.arrival} IST · {criteria.hours} hours</Text>
      {results.isLoading && <Loading />}
      {results.isSuccess && hotels.length === 0 && <Empty title="No hotels available for this slot" hint="Try a different time or date." />}
      {hotels.map((hotel) => <Card key={hotel.roomTypeId}>
        <Text style={{ fontWeight: '700', color: colors.text }}>{hotel.hotelName}</Text>
        <Text style={{ color: colors.muted, marginVertical: 6 }}>{hotel.area} · {hotel.roomName}</Text>
        <Button title={checkout.signInRequired ? 'Sign in to book' : `Book for ${inr(hotel.ratePerRoom * criteria.rooms)}`}
          disabled={checkout.isPending} onPress={() => {
            const { date, arrival, hours, rooms, guests } = criteria;
            checkout.begin({ date, arrival, hours, rooms, guests, roomTypeId: hotel.roomTypeId, ratePerRoom: hotel.ratePerRoom });
          }} />
      </Card>)}
      {results.hasNextPage && <Button title="Load more hotels" variant="ghost" onPress={() => { void results.fetchNextPage(); }} loading={results.isFetchingNextPage} />}
    </>}
  </ScrollView>;
}
