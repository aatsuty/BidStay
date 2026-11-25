CREATE TABLE hourly_stay_rates (
    room_type_id BIGINT PRIMARY KEY REFERENCES room_types(id),
    enabled BOOLEAN NOT NULL,
    three_hours NUMERIC(12,2) NOT NULL CHECK (three_hours > 0),
    six_hours NUMERIC(12,2) NOT NULL CHECK (six_hours > 0)
);

ALTER TABLE bookings
    ALTER COLUMN price_per_night DROP NOT NULL,
    ADD COLUMN hourly_rate NUMERIC(12,2),
    ADD COLUMN arrival_at TIMESTAMPTZ,
    ADD COLUMN departure_at TIMESTAMPTZ,
    ADD COLUMN blocked_until TIMESTAMPTZ,
    ADD CONSTRAINT booking_duration CHECK (
        (hourly_rate IS NULL AND price_per_night IS NOT NULL AND check_in < check_out)
        OR (hourly_rate IS NOT NULL AND hourly_rate > 0 AND price_per_night IS NULL AND check_in = check_out
            AND direct_request_id IS NOT NULL AND arrival_at IS NOT NULL AND departure_at IS NOT NULL
            AND departure_at - arrival_at IN (interval '3 hours', interval '6 hours'))
    ),
    ADD CONSTRAINT booking_occupancy_window CHECK (
        (arrival_at IS NULL AND departure_at IS NULL AND blocked_until IS NULL)
        OR (arrival_at IS NOT NULL AND departure_at IS NOT NULL AND blocked_until IS NOT NULL
            AND arrival_at < departure_at AND departure_at <= blocked_until)
    );

CREATE INDEX idx_bookings_active_room_dates ON bookings (room_type_id, check_out, check_in)
    WHERE status IN ('PENDING_PAYMENT', 'CONFIRMED');

CREATE FUNCTION room_peak_occupancy(pool BIGINT, from_time TIMESTAMPTZ, until_time TIMESTAMPTZ)
RETURNS BIGINT LANGUAGE SQL STABLE AS $$
    WITH candidates AS (
        SELECT coalesce(arrival_at, check_in::timestamp AT TIME ZONE 'Asia/Kolkata') AS starts,
               coalesce(blocked_until, (check_out + 1)::timestamp AT TIME ZONE 'Asia/Kolkata') AS ends,
               rooms
        FROM bookings
        WHERE room_type_id = pool AND status IN ('PENDING_PAYMENT', 'CONFIRMED')
          AND check_out >= (from_time AT TIME ZONE 'Asia/Kolkata')::date - 1
          AND check_in <= (until_time AT TIME ZONE 'Asia/Kolkata')::date
    ), events AS (
        SELECT greatest(starts, from_time) AS at, rooms AS delta
        FROM candidates WHERE starts < until_time AND ends > from_time
        UNION ALL
        SELECT least(ends, until_time), -rooms
        FROM candidates WHERE starts < until_time AND ends > from_time
    ), occupancy AS (
        SELECT sum(sum(delta)) OVER (ORDER BY at) AS used
        FROM events GROUP BY at
    )
    SELECT coalesce(max(used), 0)::bigint FROM occupancy
$$;

CREATE FUNCTION room_has_capacity(pool BIGINT, from_time TIMESTAMPTZ, until_time TIMESTAMPTZ, wanted INTEGER)
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT coalesce(bool_and(i.id IS NOT NULL AND i.total_rooms >= wanted + room_peak_occupancy(
        pool, greatest(from_time, local_day AT TIME ZONE 'Asia/Kolkata'),
        least(until_time, (local_day + interval '1 day') AT TIME ZONE 'Asia/Kolkata')
    )), false)
    FROM generate_series((from_time AT TIME ZONE 'Asia/Kolkata')::date::timestamp,
                         ((until_time - interval '1 microsecond') AT TIME ZONE 'Asia/Kolkata')::date::timestamp,
                         interval '1 day') AS days(local_day)
    LEFT JOIN room_inventory i ON i.room_type_id = pool AND i.stay_date = local_day::date
$$;

CREATE FUNCTION room_has_nightly_capacity(pool BIGINT, in_date DATE, out_date DATE, wanted INTEGER)
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT NOT EXISTS (SELECT 1 FROM hourly_stay_rates WHERE room_type_id = pool)
        OR room_has_capacity(pool,
            (in_date + coalesce(p.check_in_from, time '00:00')) AT TIME ZONE 'Asia/Kolkata',
            CASE WHEN p.check_out_by IS NULL
                THEN (out_date + 1)::timestamp AT TIME ZONE 'Asia/Kolkata'
                ELSE ((out_date + p.check_out_by) AT TIME ZONE 'Asia/Kolkata') + interval '30 minutes'
            END, wanted)
    FROM room_types r LEFT JOIN hotel_stay_policies p ON p.hotel_id = r.hotel_id
    WHERE r.id = pool
$$;
