CREATE TABLE location_cities (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    name_key TEXT GENERATED ALWAYS AS (lower(btrim(name))) STORED UNIQUE
);

CREATE TABLE location_city_aliases (
    alias TEXT PRIMARY KEY CHECK (alias = lower(btrim(alias))),
    city_id BIGINT NOT NULL REFERENCES location_cities(id)
);

CREATE TABLE location_regions (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    name_key TEXT GENERATED ALWAYS AS (lower(btrim(name))) STORED UNIQUE
);

CREATE TABLE location_region_cities (
    region_id BIGINT NOT NULL REFERENCES location_regions(id),
    city_id BIGINT NOT NULL REFERENCES location_cities(id),
    PRIMARY KEY (region_id, city_id)
);

CREATE INDEX idx_location_region_city ON location_region_cities (city_id, region_id);

CREATE TABLE location_neighbourhoods (
    id BIGSERIAL PRIMARY KEY,
    city_id BIGINT NOT NULL REFERENCES location_cities(id),
    name VARCHAR(100) NOT NULL,
    name_key TEXT GENERATED ALWAYS AS (lower(btrim(name))) STORED,
    UNIQUE (city_id, name_key)
);

INSERT INTO location_cities(name)
VALUES ('Delhi'), ('Noida'), ('Greater Noida'), ('Gurugram'), ('Ghaziabad'), ('Faridabad'), ('Bengaluru');

INSERT INTO location_city_aliases(alias, city_id)
SELECT a.alias, c.id FROM (VALUES ('new delhi', 'Delhi'), ('gurgaon', 'Gurugram'), ('bangalore', 'Bengaluru')) a(alias, city)
JOIN location_cities c ON c.name = a.city;

INSERT INTO location_regions(name) VALUES ('Delhi NCR');
INSERT INTO location_region_cities(region_id, city_id)
SELECT r.id, c.id FROM location_regions r CROSS JOIN location_cities c
WHERE r.name = 'Delhi NCR' AND c.name IN ('Delhi', 'Noida', 'Greater Noida', 'Gurugram', 'Ghaziabad', 'Faridabad');

ALTER TABLE hotels ADD COLUMN city_id BIGINT REFERENCES location_cities(id),
    ADD COLUMN neighbourhood_id BIGINT REFERENCES location_neighbourhoods(id),
    ADD COLUMN map_point point GENERATED ALWAYS AS (
        CASE WHEN latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180 THEN
            point(round(longitude::numeric, 2)::float8, round(latitude::numeric, 2)::float8)
        END
    ) STORED;

CREATE FUNCTION assign_hotel_location() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.city = OLD.city AND NEW.area = OLD.area AND NEW.city_id IS NOT NULL THEN
            RETURN NEW;
        END IF;
    END IF;

    SELECT a.city_id INTO NEW.city_id
    FROM location_city_aliases a WHERE a.alias = lower(btrim(NEW.city));
    IF NEW.city_id IS NULL THEN
        INSERT INTO location_cities(name) VALUES (btrim(NEW.city))
        ON CONFLICT (name_key) DO NOTHING;
        SELECT c.id INTO NEW.city_id FROM location_cities c WHERE c.name_key = lower(btrim(NEW.city));
    END IF;

    NEW.neighbourhood_id := NULL;
    IF btrim(NEW.area) <> '' THEN
        INSERT INTO location_neighbourhoods(city_id, name) VALUES (NEW.city_id, btrim(NEW.area))
        ON CONFLICT (city_id, name_key) DO NOTHING;
        SELECT n.id INTO NEW.neighbourhood_id FROM location_neighbourhoods n
        WHERE n.city_id = NEW.city_id AND n.name_key = lower(btrim(NEW.area));
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER sync_hotel_location BEFORE INSERT OR UPDATE OF city, area ON hotels
FOR EACH ROW EXECUTE FUNCTION assign_hotel_location();

UPDATE hotels SET city = city;
ALTER TABLE hotels ALTER COLUMN city_id SET NOT NULL;

CREATE INDEX idx_hotels_active_city_id ON hotels (city_id, id) WHERE status = 'ACTIVE';
CREATE INDEX idx_hotels_active_neighbourhood_id ON hotels (neighbourhood_id, id) WHERE status = 'ACTIVE';
CREATE INDEX idx_hotels_active_map_point ON hotels USING gist (map_point)
    WHERE status = 'ACTIVE' AND map_point IS NOT NULL;
CREATE INDEX idx_location_city_prefix ON location_cities (name_key text_pattern_ops);
CREATE INDEX idx_location_region_prefix ON location_regions (name_key text_pattern_ops);
CREATE INDEX idx_location_area_prefix ON location_neighbourhoods (name_key text_pattern_ops);

CREATE FUNCTION destination_cities(destination text) RETURNS SETOF bigint
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT id FROM location_cities WHERE name_key = lower(btrim(destination))
    UNION
    SELECT city_id FROM location_city_aliases WHERE alias = lower(btrim(destination))
    UNION
    SELECT rc.city_id FROM location_regions r JOIN location_region_cities rc ON rc.region_id = r.id
    WHERE r.name_key = lower(btrim(destination))
$$;

CREATE FUNCTION city_destination_keys(city bigint) RETURNS SETOF text
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT name_key FROM location_cities WHERE id = city
    UNION
    SELECT alias FROM location_city_aliases WHERE city_id = city
    UNION
    SELECT r.name_key FROM location_regions r JOIN location_region_cities rc ON rc.region_id = r.id
    WHERE rc.city_id = city
$$;

CREATE INDEX idx_location_alias_city ON location_city_aliases (city_id);
CREATE INDEX idx_location_alias_prefix ON location_city_aliases (alias text_pattern_ops);

CREATE FUNCTION hotel_in_map(location point, lat float8, lng float8, south float8, west float8, north float8, east float8)
RETURNS boolean LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT (south IS NULL OR (location IS NOT NULL AND (
               location <@ box(point(west, south), point(CASE WHEN east < west THEN 180 ELSE east END, north))
               OR (east < west AND location <@ box(point(-180, south), point(east, north)))
           )))
       AND (lat IS NULL OR (location IS NOT NULL AND
           6371 * acos(least(1, greatest(-1, sin(radians(lat)) * sin(radians(location[1]))
             + cos(radians(lat)) * cos(radians(location[1])) * cos(radians(location[0] - lng))))) <= 5))
$$;
