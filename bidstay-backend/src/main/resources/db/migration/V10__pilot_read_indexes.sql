CREATE INDEX idx_hotels_active_catalog ON hotels (lower(city), id) WHERE status = 'ACTIVE';
CREATE INDEX idx_hotels_admin_status ON hotels (status, id DESC);
