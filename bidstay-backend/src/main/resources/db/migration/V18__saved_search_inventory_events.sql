CREATE TABLE saved_search_rechecks (
    id BIGSERIAL PRIMARY KEY,
    room_type_id BIGINT NOT NULL REFERENCES room_types(id) ON DELETE CASCADE,
    from_date DATE NOT NULL,
    until_date DATE NOT NULL,
    after_search_id BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_saved_searches_candidates ON saved_stay_searches (city, category, id) WHERE enabled;
CREATE INDEX idx_guest_alerts_bid_offers ON guest_alerts (bid_id, created_at) WHERE kind = 'OFFER';

CREATE FUNCTION queue_new_inventory() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO saved_search_rechecks (room_type_id, from_date, until_date)
    SELECT room_type_id, min(stay_date), max(stay_date) + 1 FROM new_days
    WHERE total_rooms > held_rooms + sold_rooms GROUP BY room_type_id;
    RETURN NULL;
END
$$;

CREATE FUNCTION queue_released_inventory() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO saved_search_rechecks (room_type_id, from_date, until_date)
    SELECT n.room_type_id, min(n.stay_date), max(n.stay_date) + 1
    FROM new_days n JOIN old_days o ON o.id = n.id
    WHERE n.total_rooms - n.held_rooms - n.sold_rooms > o.total_rooms - o.held_rooms - o.sold_rooms
    GROUP BY n.room_type_id;
    RETURN NULL;
END
$$;

CREATE FUNCTION queue_listed_rate_drop() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO saved_search_rechecks (room_type_id, from_date, until_date)
    VALUES (NEW.id, '-infinity'::date, 'infinity'::date);
    RETURN NEW;
END
$$;

CREATE FUNCTION queue_released_hourly_stay() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO saved_search_rechecks (room_type_id, from_date, until_date)
    VALUES (NEW.room_type_id, NEW.check_in, NEW.check_out + 1);
    RETURN NEW;
END
$$;

CREATE TRIGGER new_inventory_recheck AFTER INSERT ON room_inventory
    REFERENCING NEW TABLE AS new_days FOR EACH STATEMENT EXECUTE FUNCTION queue_new_inventory();
CREATE TRIGGER released_inventory_recheck AFTER UPDATE ON room_inventory
    REFERENCING OLD TABLE AS old_days NEW TABLE AS new_days FOR EACH STATEMENT EXECUTE FUNCTION queue_released_inventory();
CREATE TRIGGER listed_rate_recheck AFTER UPDATE OF listed_price ON room_types FOR EACH ROW
    WHEN (NEW.listed_price < OLD.listed_price) EXECUTE FUNCTION queue_listed_rate_drop();
CREATE TRIGGER hourly_release_recheck AFTER UPDATE OF status ON bookings FOR EACH ROW
    WHEN (NEW.hourly_rate IS NOT NULL AND OLD.status IN ('PENDING_PAYMENT', 'CONFIRMED')
          AND NEW.status IN ('CANCELLED', 'EXPIRED')) EXECUTE FUNCTION queue_released_hourly_stay();
