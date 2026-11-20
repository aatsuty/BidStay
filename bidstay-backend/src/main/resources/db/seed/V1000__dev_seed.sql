-- DEV ONLY demo data (loaded by the "dev" profile). Inventory covers 60 days from first run.
INSERT INTO users (phone, name, role) VALUES
  ('9999900001','Test Guest','GUEST'),
  ('9999900002','Test Guest 2','GUEST'),
  ('9999900003','Hotel Owner','HOTEL_STAFF');

INSERT INTO hotels (name, city, area, address, latitude, longitude, star_rating, status, gst_number) VALUES
  ('Hotel Sunrise Paharganj','Delhi','Paharganj','Main Bazaar Rd, Paharganj',28.6430,77.2130,2,'ACTIVE','07AAAAA0000A1Z5'),
  ('Hotel Metro Inn','Delhi','Paharganj','Ramdwara Rd, Paharganj',28.6440,77.2150,2,'ACTIVE','07AAAAA0000B1Z5'),
  ('Aero Comfort Suites','Delhi','Aerocity','Asset 7, Aerocity',28.5515,77.1210,3,'ACTIVE','07AAAAA0000C1Z5'),
  ('Capital Grand','Delhi','Connaught Place','Barakhamba Rd',28.6330,77.2190,3,'ACTIVE','07AAAAA0000D1Z5');

INSERT INTO room_types (hotel_id, category, name, max_guests, listed_price, min_price, total_rooms)
SELECT h.id, v.category, v.rname, v.guests, v.listed, v.minp, 10
FROM hotels h JOIN (VALUES
  ('Hotel Sunrise Paharganj','STANDARD','Standard Room',2,1800,1200),
  ('Hotel Sunrise Paharganj','DELUXE','Deluxe Room',3,2600,1800),
  ('Hotel Metro Inn','STANDARD','Standard Room',2,1500,1000),
  ('Aero Comfort Suites','STANDARD','Standard Room',2,4200,3000),
  ('Aero Comfort Suites','DELUXE','Deluxe Room',3,5600,4000),
  ('Capital Grand','STANDARD','Standard Room',2,5000,3600)
) AS v(hname, category, rname, guests, listed, minp) ON v.hname = h.name;

INSERT INTO room_inventory (room_type_id, stay_date, total_rooms)
SELECT rt.id, d::date, rt.total_rooms
FROM room_types rt CROSS JOIN generate_series(current_date, current_date + 60, interval '1 day') AS d;

INSERT INTO hotel_staff (user_id, hotel_id)
SELECT u.id, h.id FROM users u, hotels h
WHERE u.phone = '9999900003' AND h.name IN ('Hotel Sunrise Paharganj','Hotel Metro Inn');

-- ---- Portals demo data: admin, a second owner, and two hotels waiting for approval ----
INSERT INTO users (phone, name, role) VALUES
  ('9999900004','BidStay Admin','ADMIN'),
  ('9999900005','Pending Hotel Owner','HOTEL_STAFF');

INSERT INTO hotels (name, city, area, address, latitude, longitude, star_rating, status, gst_number, contact_phone) VALUES
  ('Delhi Grand Stay','Delhi','Connaught Place','12 Janpath, Connaught Place',28.6288,77.2150,3,'PENDING_VERIFICATION','07AAAAA0000E1Z5','9811100001'),
  ('Pahar Palace','Delhi','Paharganj','45 Arakashan Rd, Paharganj',28.6410,77.2090,3,'PENDING_VERIFICATION','07AAAAA0000F1Z5','9811100002');

INSERT INTO hotel_staff (user_id, hotel_id)
SELECT u.id, h.id FROM users u, hotels h
WHERE u.phone = '9999900005' AND h.name IN ('Delhi Grand Stay','Pahar Palace');

INSERT INTO room_types (hotel_id, category, name, max_guests, listed_price, min_price, total_rooms)
SELECT h.id, 'STANDARD', 'Standard Room', 2, v.listed, v.minp, 8
FROM hotels h JOIN (VALUES ('Delhi Grand Stay',3800,2800), ('Pahar Palace',2400,1700)) AS v(hname, listed, minp) ON v.hname = h.name;

INSERT INTO room_inventory (room_type_id, stay_date, total_rooms)
SELECT rt.id, d::date, rt.total_rooms
FROM room_types rt JOIN hotels h ON h.id = rt.hotel_id CROSS JOIN generate_series(current_date, current_date + 60, interval '1 day') AS d
WHERE h.name IN ('Delhi Grand Stay','Pahar Palace');

-- Delhi Grand Stay: 3 documents submitted, photos missing. Pahar Palace: all four submitted, none verified yet.
INSERT INTO hotel_documents (hotel_id, doc_type, reference, verified)
SELECT h.id, v.t, v.ref, FALSE
FROM hotels h JOIN (VALUES
  ('Delhi Grand Stay','GST','07AAAAA0000E1Z5'), ('Delhi Grand Stay','LICENCE','DL/HTL/2019/00417'), ('Delhi Grand Stay','OWNER_ID','Aadhaar ending 4821'),
  ('Pahar Palace','GST','07AAAAA0000F1Z5'), ('Pahar Palace','LICENCE','DL/HTL/2021/01102'), ('Pahar Palace','OWNER_ID','PAN ending 7731'), ('Pahar Palace','PHOTOS','https://example.com/pahar-palace-photos')
) AS v(hname, t, ref) ON v.hname = h.name;

INSERT INTO partner_messages (hotel_id, sender, kind, body)
SELECT id, 'BOT', 'SYSTEM', '*Welcome to BidStay.* Bid requests for ' || name || ' will appear in this chat. Reply *YES* to accept, *NO* to decline, or *COUNTER 1500* with your price.'
FROM hotels WHERE status = 'ACTIVE';
