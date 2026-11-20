import React, { useState } from 'react';
import { ScrollView, Text, View } from 'react-native';
import { router } from 'expo-router';
import { Button, Card, Chip, Field, Stepper } from '@/components/ui';
import { addDays, formatDate, nightsBetween, toISODate } from '@/lib/format';
import { colors } from '@/lib/theme';
import type { RoomCategory } from '@/lib/types';

const CATEGORIES: { key: RoomCategory; label: string }[] = [
  { key: 'STANDARD', label: 'Standard' },
  { key: 'DELUXE', label: 'Deluxe' },
  { key: 'SUITE', label: 'Suite' },
];

export default function SearchScreen() {
  const today = toISODate(new Date());
  const [city, setCity] = useState('Delhi');
  const [checkIn, setCheckIn] = useState(addDays(today, 1));
  const [nights, setNights] = useState(1);
  const [rooms, setRooms] = useState(1);
  const [guests, setGuests] = useState(2);
  const [category, setCategory] = useState<RoomCategory>('STANDARD');

  const checkOut = addDays(checkIn, nights);

  function search() {
    router.push({
      pathname: '/hotels',
      params: { city: city.trim(), checkIn, checkOut, rooms: String(rooms), guests: String(guests), category },
    });
  }

  return (
    <ScrollView contentContainerStyle={{ padding: 16 }} keyboardShouldPersistTaps="handled">
      <View style={{ marginBottom: 16 }}>
        <Text style={{ fontSize: 28, fontWeight: '800', color: colors.primaryDark }}>BidStay</Text>
        <Text style={{ color: colors.muted, marginTop: 2 }}>Apna Rate, Apna Stay — you name the price.</Text>
      </View>

      <Card>
        <Field label="City" value={city} onChangeText={setCity} autoCapitalize="words" />

        <Stepper
          label="Check-in"
          value={formatDate(checkIn)}
          onDec={() => setCheckIn(addDays(checkIn, -1))}
          onInc={() => setCheckIn(addDays(checkIn, 1))}
          decDisabled={nightsBetween(today, checkIn) <= 0}
        />
        <Stepper
          label="Nights"
          value={`${nights} night${nights > 1 ? 's' : ''}`}
          onDec={() => setNights(nights - 1)}
          onInc={() => setNights(nights + 1)}
          decDisabled={nights <= 1}
          incDisabled={nights >= 30}
        />
        <Text style={{ color: colors.muted, fontSize: 12, textAlign: 'right', marginTop: -4 }}>Check-out {formatDate(checkOut)}</Text>
        <Stepper label="Rooms" value={String(rooms)} onDec={() => setRooms(rooms - 1)} onInc={() => setRooms(rooms + 1)} decDisabled={rooms <= 1} incDisabled={rooms >= 10} />
        <Stepper label="Guests" value={String(guests)} onDec={() => setGuests(guests - 1)} onInc={() => setGuests(guests + 1)} decDisabled={guests <= 1} incDisabled={guests >= 40} />

        <Text style={{ fontSize: 13, color: colors.muted, fontWeight: '600', marginTop: 10, marginBottom: 8 }}>Room type</Text>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap' }}>
          {CATEGORIES.map((c) => (
            <Chip key={c.key} label={c.label} selected={category === c.key} onPress={() => setCategory(c.key)} />
          ))}
        </View>
      </Card>

      <Button title="Find hotels" onPress={search} disabled={!city.trim()} />

      <Card style={{ marginTop: 16, backgroundColor: colors.primarySoft, borderColor: colors.primarySoft }}>
        <Text style={{ fontWeight: '700', color: colors.primaryDark, marginBottom: 4 }}>How it works</Text>
        <Text style={{ color: colors.primaryDark }}>
          1. Pick hotels and name your price.{'\n'}2. Hotels accept, counter, or decline.{'\n'}3. Choose the best offer and pay.
        </Text>
      </Card>
    </ScrollView>
  );
}
