CREATE INDEX idx_hotels_active_destination_prefix
    ON hotels (lower(city) text_pattern_ops) INCLUDE (city, id)
    WHERE status = 'ACTIVE';
