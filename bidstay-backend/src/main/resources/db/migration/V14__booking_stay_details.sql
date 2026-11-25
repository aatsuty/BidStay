CREATE TABLE hotel_stay_policies (
    hotel_id BIGINT PRIMARY KEY REFERENCES hotels(id),
    check_in_from TIME NOT NULL,
    check_out_by TIME NOT NULL,
    guest_instructions VARCHAR(1000)
);

ALTER TABLE bookings
    ADD COLUMN hotel_name_at_booking VARCHAR(150),
    ADD COLUMN hotel_address_at_booking VARCHAR(300),
    ADD COLUMN room_name_at_booking VARCHAR(80),
    ADD COLUMN check_in_from TIME,
    ADD COLUMN check_out_by TIME,
    ADD COLUMN guest_instructions VARCHAR(1000);
