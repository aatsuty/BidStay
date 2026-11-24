import React, { useState } from 'react';
import { ScrollView, Text, View } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { BidBadge, Button, Card, ErrorText, Field, Loading, OfferBadge } from '@/components/ui';
import { errorMessage, useApi } from '@/lib/api';
import { countdown, formatDate, inr, nightsBetween, useNow } from '@/lib/format';
import { colors } from '@/lib/theme';
import { useSession } from '@/lib/session';
import type { BidView, BookingView, OfferView } from '@/lib/types';

export default function BidScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const api = useApi();
  const { cacheKey } = useSession();
  const qc = useQueryClient();
  const now = useNow();
  const [raiseTo, setRaiseTo] = useState('');

  // Poll while hotels may still be replying. (Later: WebSocket/push instead of polling.)
  const bidQuery = useQuery({
    queryKey: ['bid', id, cacheKey],
    queryFn: () => api.get<BidView>(`/api/v1/bids/${id}`),
    refetchInterval: (q) => (q.state.data?.status === 'OPEN' ? 4000 : false),
  });
  const bid = bidQuery.data;

  const raise = useMutation({
    mutationFn: (price: number) => api.post<BidView>(`/api/v1/bids/${id}/raise`, { pricePerNight: price }),
    onSuccess: (data) => {
      qc.setQueryData(['bid', id, cacheKey], data);
      qc.invalidateQueries({ queryKey: ['bids'] });
      setRaiseTo('');
    },
  });

  const accept = useMutation({
    mutationFn: (offerId: number) => api.post<BookingView>(`/api/v1/bids/${id}/offers/${offerId}/accept`),
    onSuccess: (booking) => {
      qc.invalidateQueries({ queryKey: ['bid', id, cacheKey] });
      qc.invalidateQueries({ queryKey: ['bids'] });
      router.replace(`/booking/${booking.bookingId}`);
    },
  });

  if (bidQuery.isLoading) return <Loading />;
  if (!bid) return <ErrorText message={errorMessage(bidQuery.error)} />;

  const open = bid.status === 'OPEN';
  const nights = nightsBetween(bid.checkIn, bid.checkOut);
  const raiseValue = Number(raiseTo);
  const canRaise = open && bid.round < bid.maxRounds;
  const raiseValid = Number.isFinite(raiseValue) && raiseValue > bid.pricePerNight;
  const waiting = bid.offers.filter((o) => o.status === 'PENDING').length;
  const bookable = bid.offers.filter((o) => o.status === 'ACCEPTED' || o.status === 'COUNTERED').length;

  return (
    <ScrollView contentContainerStyle={{ padding: 16 }} keyboardShouldPersistTaps="handled">
      <Card>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
          <Text style={{ fontSize: 22, fontWeight: '800', color: colors.text }}>{inr(bid.pricePerNight)} / night</Text>
          <BidBadge status={bid.status} />
        </View>
        <Text style={{ color: colors.muted, marginTop: 6 }}>
          {formatDate(bid.checkIn)} → {formatDate(bid.checkOut)} · {nights} night{nights > 1 ? 's' : ''} · {bid.rooms} room{bid.rooms > 1 ? 's' : ''}
        </Text>
        <Text style={{ color: colors.muted, marginTop: 2 }}>
          Round {bid.round} of {bid.maxRounds}
          {open ? ` · ${waiting} waiting · ${bookable} ready to book` : ''}
        </Text>
      </Card>

      {bid.status === 'HELD' || bid.status === 'BOOKED' ? (
        bid.bookingId ? <Button title={bid.status === 'BOOKED' ? 'View booking' : 'Continue to payment'} onPress={() => router.replace(`/booking/${bid.bookingId}`)} style={{ marginBottom: 12 }} /> : null
      ) : null}

      <ErrorText message={accept.error ? errorMessage(accept.error) : null} />

      <Text style={{ fontSize: 16, fontWeight: '700', color: colors.text, marginBottom: 8 }}>Hotel responses</Text>
      {bid.offers.map((o) => (
        <OfferCard
          key={o.offerId}
          offer={o}
          now={now}
          canBook={open}
          booking={accept.isPending && accept.variables === o.offerId}
          onBook={() => accept.mutate(o.offerId)}
        />
      ))}

      {canRaise && (
        <Card style={{ backgroundColor: colors.primarySoft, borderColor: colors.primarySoft }}>
          <Text style={{ fontWeight: '700', color: colors.primaryDark, marginBottom: 4 }}>Not enough offers? Raise your bid</Text>
          <Text style={{ color: colors.primaryDark, marginBottom: 12 }}>
            Hotels that declined, countered or didn&apos;t reply get another look. Offers already accepted stay at their price. You can raise {bid.maxRounds - bid.round} more time{bid.maxRounds - bid.round > 1 ? 's' : ''}.
          </Text>
          <ErrorText message={raise.error ? errorMessage(raise.error) : null} />
          <Field
            label={`New price per night (above ${inr(bid.pricePerNight)})`}
            value={raiseTo}
            onChangeText={(t) => setRaiseTo(t.replace(/[^0-9.]/g, ''))}
            keyboardType="numeric"
            placeholder={String(Math.round(bid.pricePerNight * 1.1))}
          />
          <Button title="Raise bid" onPress={() => raise.mutate(raiseValue)} disabled={!raiseValid} loading={raise.isPending} />
        </Card>
      )}
      {open && !canRaise && <Text style={{ color: colors.muted, textAlign: 'center' }}>You&apos;ve used all {bid.maxRounds} rounds. Pick an offer above.</Text>}
    </ScrollView>
  );
}

function OfferCard({ offer, now, canBook, booking, onBook }: { offer: OfferView; now: number; canBook: boolean; booking: boolean; onBook: () => void }) {
  const left = offer.status === 'PENDING' ? countdown(offer.expiresAt, now) : null;
  const bookable = canBook && (offer.status === 'ACCEPTED' || offer.status === 'COUNTERED');
  const dim = offer.status === 'UNAVAILABLE' || offer.status === 'EXPIRED' || offer.status === 'CANCELLED' || offer.status === 'DECLINED';
  return (
    <Card style={dim ? { opacity: 0.6 } : undefined}>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <View style={{ flex: 1, paddingRight: 8 }}>
          <Text style={{ fontSize: 16, fontWeight: '700', color: colors.text }}>{offer.hotelName}</Text>
          <Text style={{ color: colors.muted, marginTop: 2 }}>{offer.area}</Text>
        </View>
        <OfferBadge status={offer.status} />
      </View>

      {offer.pricePerNight != null && (
        <View style={{ marginTop: 10, flexDirection: 'row', alignItems: 'baseline' }}>
          <Text style={{ fontSize: 20, fontWeight: '800', color: colors.text }}>{inr(offer.pricePerNight)}</Text>
          <Text style={{ color: colors.muted }}> / night · total {inr(offer.totalForStay)}</Text>
        </View>
      )}
      {offer.autoAccepted && offer.status === 'ACCEPTED' && <Text style={{ color: colors.success, marginTop: 4, fontSize: 12, fontWeight: '600' }}>Instantly accepted at your price</Text>}
      {offer.status === 'COUNTERED' && <Text style={{ color: colors.warn, marginTop: 4, fontSize: 12 }}>{offer.autoAccepted ? 'The hotel automatically proposed this price, close to your bid.' : 'The hotel proposes this price instead of yours.'}</Text>}
      {offer.status === 'PENDING' && (
        <Text style={{ color: colors.muted, marginTop: 8 }}>{left ? `Hotel replies within ${left}` : 'Time is up, updating…'}</Text>
      )}
      {bookable && <Button title={`Book at ${inr(offer.pricePerNight)}`} onPress={onBook} loading={booking} style={{ marginTop: 12 }} />}
    </Card>
  );
}
