import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const api = new URL(process.env.BIDSTAY_LOAD_URL || 'http://localhost:8080');
assert.ok(['localhost', '127.0.0.1', '[::1]'].includes(api.hostname), 'Use a local development API with an isolated database');
const concurrency = Number(process.env.BIDSTAY_LOAD_CONCURRENCY || 12);
assert.ok(Number.isInteger(concurrency) && concurrency > 0 && concurrency <= 50, 'Set concurrency from 1 to 50');
const day = (offset) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);

async function request(path, { method = 'GET', user, body } = {}) {
  const start = performance.now();
  const response = await fetch(new URL(path, api), {
    method, signal: AbortSignal.timeout(20_000),
    headers: { ...(user ? { 'X-User-Id': String(user) } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  return { status: response.status, data: text ? JSON.parse(text) : undefined, ms: performance.now() - start };
}

function searchFor(checkIn, checkOut) {
  return '/api/v1/hotels/search?' + new URLSearchParams({
    city: 'Delhi', checkIn, checkOut, category: 'STANDARD', rooms: '1', guests: '2',
  });
}

async function measure(label, count, run) {
  const start = performance.now();
  let next = 0;
  const results = [];
  await Promise.all(Array.from({ length: Math.min(concurrency, count) }, async () => {
    while (next < count) {
      const index = next++;
      results.push(await run(index));
    }
  }));
  const ms = results.map((result) => result.ms).sort((a, b) => a - b);
  const countByStatus = {};
  for (const result of results) countByStatus[result.status] = (countByStatus[result.status] ?? 0) + 1;
  console.log(JSON.stringify({
    label, requests: count, concurrency: Math.min(concurrency, count), statuses: countByStatus,
    throughputsPerSecond: Number((count / ((performance.now() - start) / 1000)).toFixed(1)),
    p95Ms: Number(ms[Math.ceil(count * 0.95) - 1].toFixed(1)),
  }));
  return results;
}

const checkIn = day(19);
const checkOut = day(20);
const search = searchFor(checkIn, checkOut);
const sample = await request(search);
assert.equal(sample.status, 200);
assert.ok(sample.data.hotels.length >= 2, 'Use the seeded Delhi catalogue');

const reads = await measure('dated search', 180, () => request(search));
assert.ok(reads.every((result) => result.status === 200));

const hotelIds = sample.data.hotels.slice(0, 12).map((hotel) => hotel.id);
const bids = await measure('bid fan-out to ' + hotelIds.length + ' hotels', 48, (index) =>
  request('/api/v1/bids', {
    method: 'POST', user: 1 + index % 2,
    body: { city: 'Delhi', checkIn, checkOut, rooms: 1, guests: 2, category: 'STANDARD', pricePerNight: 1100, hotelIds },
  }));
assert.ok(bids.every((result) => result.status === 201 && result.data.offers.length === hotelIds.length));

const lastRoomDay = day(25);
const nextDay = day(26);
const hotel = sample.data.hotels.find((item) => item.name === 'Hotel Sunrise Paharganj');
assert.ok(hotel, 'Use the seeded Hotel Sunrise Paharganj');
const capacity = await request(`/api/v1/partner/hotels/${hotel.id}/availability`, {
  method: 'PUT', user: 3,
  body: { roomTypeId: hotel.roomTypeId, from: lastRoomDay, to: nextDay, totalRooms: 1 },
});
assert.equal(capacity.status, 200);

const slot = await request('/api/v1/hotels/hourly?' + new URLSearchParams({
  city: 'Delhi', date: lastRoomDay, arrival: '12:00', hours: '6',
}));
const hourly = slot.status === 200 && slot.data.hotels.find((item) => item.roomTypeId === hotel.roomTypeId);
const holds = await measure(hourly ? 'last room across hourly and overnight' : 'last overnight room', 24, (index) => {
  const base = { requestId: randomUUID(), roomTypeId: hotel.roomTypeId, rooms: 1, guests: 2 };
  const dayStay = hourly && index % 2 === 0;
  return request(dayStay ? '/api/v1/bookings/hourly' : '/api/v1/bookings/listed', {
    method: 'POST', user: 1,
    body: dayStay
      ? { ...base, date: lastRoomDay, arrival: '12:00', hours: 6, ratePerRoom: hourly.ratePerRoom }
      : { ...base, checkIn: lastRoomDay, checkOut: nextDay, listedRate: hotel.listedPrice },
  });
});
const winners = holds.filter((result) => result.status === 201);
for (const hold of winners) {
  const released = await request(`/api/v1/bookings/${hold.data.bookingId}/cancel`, { method: 'POST', user: 1 });
  assert.equal(released.status, 200);
}
assert.equal(winners.length, 1);
assert.ok(holds.every((result) => result.status === 201 || result.status === 409));
