ALTER TABLE bookings ADD COLUMN free_cancel_until TIMESTAMPTZ;
ALTER TABLE bookings ADD COLUMN cancelled_at TIMESTAMPTZ;
