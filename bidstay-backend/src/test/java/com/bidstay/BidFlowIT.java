package com.bidstay;

import com.bidstay.bidding.BidDtos.*;
import com.bidstay.alerts.GuestAlerts;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bidstay.bidding.BidService;
import com.bidstay.bidding.BidStatus;
import com.bidstay.bidding.HotelAction;
import com.bidstay.bidding.OfferStatus;
import com.bidstay.booking.BookingService;
import com.bidstay.booking.BookingController.HourlyStay;
import com.bidstay.booking.BookingController.ListedStay;
import com.bidstay.booking.BookingStatus;
import com.bidstay.booking.BookingView;
import com.bidstay.common.ApiException;
import com.bidstay.hotel.HotelRepository;
import com.bidstay.hotel.HotelStatus;
import com.bidstay.hotel.RoomCategory;
import com.bidstay.hotel.HourlyStays;
import com.bidstay.inventory.InventoryService;
import com.bidstay.partner.InventoryJobs;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs against a real PostgreSQL (needs Docker). The "dev" profile loads the seed data:
 * Hotel Sunrise Paharganj, STANDARD room: listed 1800, hidden minimum 1200, 10 rooms.
 * Guests are user ids 1 and 2. Each test uses its own dates so tests do not share inventory.
 */
@SpringBootTest(properties = "bidstay.documents.local-path=${java.io.tmpdir}/bidstay-document-integration")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("dev")
class BidFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired BidService bidService;
    @Autowired BookingService bookingService;
    @Autowired HotelRepository hotels;
    @Autowired Flyway flyway;
    @Autowired MockMvc mvc;
    @Autowired InventoryJobs inventoryJobs;
    @Autowired ObjectMapper json;
    @Autowired GuestAlerts alerts;
    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryService inventory;
    @Autowired HourlyStays hourly;

    @Test
    void devDemoAccountsStillReachTheirRoutes() throws Exception {
        mvc.perform(get("/api/v1/bids").header("X-User-Id", 1))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/stats").header("X-User-Id", 4))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").header("X-User-Id", 3))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("HOTEL_STAFF"));
        mvc.perform(get("/api/v1/admin/stats").header("X-User-Id", 4).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void futureInventoryPublicationCanBeRetried() throws Exception {
        inventoryJobs.rollForward();
        inventoryJobs.rollForward();
        LocalDate date = LocalDate.now().plusDays(75);
        mvc.perform(get("/api/v1/hotels/search").param("city", "Delhi").param("checkIn", date.toString())
                        .param("checkOut", date.plusDays(1).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hotels[0].id").value(sunrise()));
    }

    @Test
    void searchFiltersByNightCapacityAndKeepsAddressesPrivate() throws Exception {
        LocalDate date = LocalDate.now().plusDays(3);
        mvc.perform(get("/api/v1/hotels/search").param("city", "Delhi").param("checkIn", date.toString())
                        .param("checkOut", date.plusDays(1).toString()).param("rooms", "10").param("guests", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hotels[0].address").doesNotExist())
                .andExpect(jsonPath("$.hotels[0].latitude").value(28.64));
        mvc.perform(get("/api/v1/hotels/search").param("city", "Delhi").param("checkIn", date.toString())
                        .param("checkOut", date.plusDays(1).toString()).param("rooms", "10").param("guests", "40"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hotels").isEmpty());
    }

    @Test
    void documentFilesArePrivateAndReviewsRequireTheCurrentVersion() throws Exception {
        long hotel = hotels.findByName("Pahar Palace").orElseThrow().getId();
        var file = new MockMultipartFile("file", "registration.pdf", "application/pdf", "%PDF-1.5 demo".getBytes());
        String endpoint = "/api/v1/partner/hotels/{id}/documents/GST/file";
        mvc.perform(multipart(endpoint, hotel).file(file).header("X-User-Id", 3))
                .andExpect(status().isForbidden());
        String uploaded = mvc.perform(multipart(endpoint, hotel).file(file).header("X-User-Id", 5))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long versionId = json.readTree(uploaded).get(0).path("versionId").asLong();
        assertTrue(versionId > 0);
        mvc.perform(get("/api/v1/admin/hotels/{id}/documents/GST/files/{version}", hotel, versionId).header("X-User-Id", 5))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/hotels/{id}/documents/GST/files/{version}", hotel, versionId).header("X-User-Id", 4))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/hotels/{id}/documents/GST/verify", hotel).header("X-User-Id", 4))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/admin/hotels/{id}/documents/GST/verify", hotel).param("versionId", String.valueOf(versionId)).header("X-User-Id", 4))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documents[0].verified").value(true));
    }

    @Test
    void publishedHotelAndRoomPhotosFlowFromPartnerToAdminToGuests() throws Exception {
        long hotelId = sunrise();
        String partner = "/api/v1/partner/hotels/" + hotelId + "/media";
        String gallery = mvc.perform(get(partner).header("X-User-Id", 3)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long roomId = 0;
        for (var room : json.readTree(gallery).path("rooms")) {
            if ("Standard Room".equals(room.path("name").asText())) roomId = room.path("id").asLong();
        }
        assertTrue(roomId > 0);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(800, 500, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var file = new MockMultipartFile("file", "room.png", "image/png", bytes.toByteArray());
        mvc.perform(multipart(partner).file(file).header("X-User-Id", 1))
                .andExpect(status().isForbidden());
        mvc.perform(multipart(partner).file(file).param("caption", "Hotel entrance").header("X-User-Id", 3))
                .andExpect(status().isCreated());
        mvc.perform(multipart(partner).file(file).param("roomTypeId", String.valueOf(roomId))
                .param("caption", "Standard bedroom").header("X-User-Id", 3)).andExpect(status().isCreated());

        var queue = json.readTree(mvc.perform(get("/api/v1/admin/media").header("X-User-Id", 4))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("photos");
        assertEquals(2, queue.size());
        long roomPhoto = queue.get(0).path("id").asLong();
        long hotelPhoto = queue.get(1).path("id").asLong();
        mvc.perform(get("/api/v1/media/{photo}/full", roomPhoto)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/media/{photo}/preview", roomPhoto).header("X-User-Id", 3))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/media/{photo}/preview", roomPhoto).header("X-User-Id", 4))
                .andExpect(status().isOk());
        for (long id : new long[]{roomPhoto, hotelPhoto}) {
            mvc.perform(post("/api/v1/admin/media/{id}/review", id).header("X-User-Id", 4)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                    .andExpect(status().isNoContent());
        }
        LocalDate day = LocalDate.now().plusDays(12);
        mvc.perform(get("/api/v1/hotels/search").param("city", "Delhi").param("checkIn", day.toString())
                        .param("checkOut", day.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hotels[0].hotelPhoto").value("/api/v1/media/" + hotelPhoto + "/thumb"))
                .andExpect(jsonPath("$.hotels[0].roomPhoto").value("/api/v1/media/" + roomPhoto + "/thumb"));
        mvc.perform(get("/api/v1/hotels/{id}", hotelId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.photos[0].caption").value("Hotel entrance"))
                .andExpect(jsonPath("$.rooms[?(@.name == 'Standard Room')].photos[0].caption").value(org.hamcrest.Matchers.hasItem("Standard bedroom")))
                .andExpect(jsonPath("$.rooms[0].minimumPrice").doesNotExist())
                .andExpect(jsonPath("$.address").doesNotExist());
        var thumbnail = mvc.perform(get("/api/v1/media/{photo}/thumb", roomPhoto)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals(480, ImageIO.read(new java.io.ByteArrayInputStream(thumbnail)).getWidth());
        mvc.perform(post(partner + "/{photo}/withdraw", roomPhoto).header("X-User-Id", 3))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/media/{photo}/thumb", roomPhoto)).andExpect(status().isNotFound());
    }

    @Test
    void existingApprovedPhotoEvidenceCanBePublishedExplicitly() throws Exception {
        long hotelId = hotels.findByName("Hotel Metro Inn").orElseThrow().getId();
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(120, 100, BufferedImage.TYPE_INT_RGB), "jpg", bytes);
        String document = "/api/v1/partner/hotels/" + hotelId + "/documents/PHOTOS/file";
        var file = new MockMultipartFile("file", "entrance.jpg", "image/jpeg", bytes.toByteArray());
        String uploaded = mvc.perform(multipart(document).file(file).header("X-User-Id", 3))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long version = 0;
        for (var row : json.readTree(uploaded)) if ("PHOTOS".equals(row.path("type").asText())) version = row.path("versionId").asLong();
        assertTrue(version > 0);
        String url = "/api/v1/admin/hotels/" + hotelId + "/documents/PHOTOS/";
        mvc.perform(post(url + "publish").param("versionId", String.valueOf(version)).header("X-User-Id", 4))
                .andExpect(status().isConflict());
        mvc.perform(post(url + "verify").param("versionId", String.valueOf(version)).header("X-User-Id", 4))
                .andExpect(status().isOk());
        mvc.perform(post(url + "publish").param("versionId", String.valueOf(version)).header("X-User-Id", 4))
                .andExpect(status().isCreated());
        mvc.perform(post(url + "publish").param("versionId", String.valueOf(version)).header("X-User-Id", 4))
                .andExpect(status().isConflict());
        // Verification replacement moves an active property back to review. Its gallery stays private until it is live.
        mvc.perform(get("/api/v1/hotels/{id}", hotelId)).andExpect(status().isNotFound());
        var hotel = hotels.findById(hotelId).orElseThrow();
        try {
            hotel.setStatus(HotelStatus.ACTIVE);
            hotels.save(hotel);
            mvc.perform(get("/api/v1/hotels/{id}", hotelId)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.photos[0].caption").value("Property photo"));
        } finally {
            hotel.setStatus(HotelStatus.ACTIVE);
            hotels.save(hotel);
        }
    }

    @Test
    void savedSearchAlertsAreOwnedAndReadOnce() throws Exception {
        LocalDate date = LocalDate.now().plusDays(14);
        String body = """
                {"city":"Delhi","area":"Paharganj","checkIn":"%s","checkOut":"%s",
                   "category":"STANDARD","rooms":1,"guests":2,"budget":8000}
                """.formatted(date, date.plusDays(1));
        String saved = mvc.perform(post("/api/v1/guest/saved-searches").header("X-User-Id", 2)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(saved).path("id").asLong();
        alerts.checkDue();
        mvc.perform(get("/api/v1/guest/alerts").header("X-User-Id", 2))
                .andExpect(status().isOk()).andExpect(jsonPath("$.alerts[0].kind").value("PRICE_MATCH"));
        mvc.perform(get("/api/v1/guest/alerts").header("X-User-Id", 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.alerts[?(@.searchId == " + id + ")]").isEmpty());
        mvc.perform(put("/api/v1/guest/saved-searches/{id}", id).header("X-User-Id", 1)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/guest/alerts/read").header("X-User-Id", 2)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/guest/alerts").header("X-User-Id", 2))
                .andExpect(status().isOk()).andExpect(jsonPath("$.alerts[0].read").value(true));
    }

    @Test
    void upgradesPreviouslySeededDevDatabase(@TempDir Path originalMigrations) throws IOException {
        for (String script : List.of("migration/V1__core_schema.sql", "migration/V2__partner_admin.sql", "seed/V1000__dev_seed.sql")) {
            try (var stream = new ClassPathResource("db/" + script).getInputStream()) {
                Files.copy(stream, originalMigrations.resolve(Path.of(script).getFileName()));
            }
        }
        String schema = "existing_dev";
        Flyway original = Flyway.configure()
                .configuration(flyway.getConfiguration())
                .defaultSchema(schema)
                .schemas(schema)
                .locations("filesystem:" + originalMigrations)
                .load();
        assertEquals(3, original.migrate().migrationsExecuted);
        assertEquals(MigrationVersion.fromVersion("1000"), original.info().current().getVersion());

        Flyway upgraded = Flyway.configure()
                .configuration(flyway.getConfiguration())
                .defaultSchema(schema)
                .schemas(schema)
                .load();
        assertTrue(upgraded.migrate().migrationsExecuted > 0);
        assertTrue(Arrays.stream(upgraded.info().applied())
                .anyMatch(migration -> MigrationVersion.fromVersion("3").equals(migration.getVersion())
                        && migration.getState() == MigrationState.OUT_OF_ORDER));
        upgraded.validate();
    }

    Long sunrise() {
        return hotels.findByName("Hotel Sunrise Paharganj").orElseThrow().getId();
    }

    CreateBid bid(int dayOffset, int rooms, String price) {
        LocalDate in = LocalDate.now().plusDays(dayOffset);
        return new CreateBid("Delhi", in, in.plusDays(2), rooms, 2, RoomCategory.STANDARD,
                new BigDecimal(price), List.of(sunrise()));
    }

    @Test
    void bidAboveHiddenMinimumIsAutoAccepted() {
        BidView v = bidService.create(1L, bid(5, 1, "1300"));
        OfferView o = v.offers().get(0);
        assertEquals(OfferStatus.ACCEPTED, o.status());
        assertTrue(o.autoAccepted());
        assertEquals(0, new BigDecimal("1300").compareTo(o.pricePerNight()));
    }

    @Test
    void counterOfferThenPayConfirmsBookingAndRevealsHotel() {
        BidView v = bidService.create(1L, bid(10, 1, "1100"));
        Long offerId = v.offers().get(0).offerId();
        assertEquals(OfferStatus.PENDING, v.offers().get(0).status());

        OfferStatus after = bidService.respond(sunrise(), offerId, new HotelRespond(HotelAction.COUNTER, new BigDecimal("1500"), 1));
        assertEquals(OfferStatus.COUNTERED, after);

        BookingView booking = bidService.acceptOffer(1L, v.bidId(), offerId);
        assertEquals(BookingStatus.PENDING_PAYMENT, booking.status());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> bidService.cancel(1L, v.bidId())).getStatus());
        assertEquals(0, new BigDecimal("3000.00").compareTo(booking.totalAmount()));   // 1500 x 2 nights x 1 room
        assertNull(booking.hotelAddress());                                           // hidden until paid

        BookingView paid = bookingService.confirmPayment(booking.bookingId(), "pay_test_1");
        assertEquals(BookingStatus.CONFIRMED, paid.status());
        assertNotNull(paid.hotelAddress());
        // duplicate webhook is harmless
        assertEquals(BookingStatus.CONFIRMED, bookingService.confirmPayment(booking.bookingId(), "pay_test_1").status());
    }

    @Test
    void guestCanWithdrawOpenBidWithoutCreatingBooking() throws Exception {
        LocalDate arrival = LocalDate.now().plusDays(19);
        Long metro = hotels.findByName("Hotel Metro Inn").orElseThrow().getId();
        var bid = bidService.create(1L, new CreateBid("Delhi", arrival, arrival.plusDays(1), 1, 2,
                RoomCategory.STANDARD, new BigDecimal("1100"), List.of(sunrise(), metro)));
        var pending = bid.offers().stream().filter(offer -> offer.status() == OfferStatus.PENDING).findFirst().orElseThrow();
        var accepted = bid.offers().stream().filter(offer -> offer.status() == OfferStatus.ACCEPTED).findFirst().orElseThrow();

        String url = "/api/v1/bids/" + bid.bidId() + "/cancel";
        mvc.perform(post(url).header("X-User-Id", 2)).andExpect(status().isNotFound());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(url).header("X-User-Id", 1)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.bookingId").doesNotExist())
                    .andExpect(jsonPath("$.offers[0].status").value("CANCELLED"))
                    .andExpect(jsonPath("$.offers[1].status").value("CANCELLED"));
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_events where subject_id = ? and action = 'BID_CANCELLED'",
                Integer.class, bid.bidId()));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from partner_messages where bid_offer_id = ? and body = 'The guest withdrew this bid request.'
                """, Integer.class, pending.offerId()));
        assertFalse(bidService.pendingForHotel(sunrise()).stream().anyMatch(offer -> offer.offerId().equals(pending.offerId())));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> bidService.acceptOffer(1L, bid.bidId(), accepted.offerId())).getStatus());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> bidService.respond(sunrise(), pending.offerId(),
                new HotelRespond(HotelAction.DECLINE, null, 1))).getStatus());
    }

    @Test
    void postStayReviewIsOwnedModeratedAndPublicWithoutGuestIdentity() throws Exception {
        Long roomType = jdbc.queryForObject("select id from room_types where hotel_id = ? and category = 'STANDARD'", Long.class, sunrise());
        LocalDate future = LocalDate.now().plusDays(27);
        var hold = bookingService.startListed(1L, new ListedStay(UUID.randomUUID(), roomType, future, future.plusDays(1),
                1, 2, new BigDecimal("1800")));
        bookingService.confirmPayment(hold.bookingId(), "review_demo_it");
        String review = "/api/v1/bookings/" + hold.bookingId() + "/review";
        String input = """
                {"rating":5,"comment":"A clean room."}
                """;
        mvc.perform(post(review).header("X-User-Id", 1).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isConflict());
        jdbc.update("""
                update bookings set check_in = current_date - 4, check_out = current_date - 3,
                    arrival_at = null, departure_at = null, blocked_until = null where id = ?
                """, hold.bookingId());
        mvc.perform(post(review).header("X-User-Id", 2).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isNotFound());
        mvc.perform(post(review).header("X-User-Id", 1).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isNoContent());
        mvc.perform(post(review).header("X-User-Id", 1).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isConflict());
        String publicReviews = "/api/v1/hotels/" + sunrise() + "/reviews";
        mvc.perform(get(publicReviews)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        String publish = "/api/v1/admin/reviews/" + hold.bookingId() + "/PUBLISHED";
        mvc.perform(post(publish).header("X-User-Id", 3)).andExpect(status().isForbidden());
        mvc.perform(post(publish).header("X-User-Id", 4)).andExpect(status().isNoContent());
        mvc.perform(get(publicReviews)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rating").value(5)).andExpect(jsonPath("$[0].guestId").doesNotExist())
                .andExpect(jsonPath("$[0].bookingId").doesNotExist());
    }

    @Test
    void listedRateDropWakesMatchingSavedSearchBeforeScanIsDue() throws Exception {
        Long roomType = jdbc.queryForObject("select id from room_types where hotel_id = ? and category = 'STANDARD'", Long.class, sunrise());
        LocalDate date = LocalDate.now().plusDays(28);
        var request = new GuestAlerts.SaveSearch("Delhi", "Paharganj", date, date.plusDays(1), RoomCategory.STANDARD, 1, 2,
                new BigDecimal("1250"));
        var response = mvc.perform(post("/api/v1/guest/saved-searches").header("X-User-Id", 1)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn().getResponse();
        long searchId = json.readTree(response.getContentAsString()).path("id").asLong();
        jdbc.update("update saved_stay_searches set next_check_at = now() + interval '1 day' where id = ?", searchId);
        try {
            jdbc.update("update room_types set listed_price = 1249 where id = ?", roomType);
            for (int attempt = 0; attempt < 8; attempt++) alerts.checkDue();
            assertEquals(1, jdbc.queryForObject("""
                    select count(*) from guest_alerts where guest_id = 1 and event_key = ?
                    """, Integer.class, "search-" + searchId + "-" + sunrise()));
        } finally {
            jdbc.update("update room_types set listed_price = 1800 where id = ?", roomType);
        }
    }

    @Test
    void listedCheckoutUsesAuthoritativeRateAndIsPrivateAndIdempotent() throws Exception {
        Long roomType = jdbc.queryForObject("select id from room_types where hotel_id = ? and category = 'STANDARD'", Long.class, sunrise());
        LocalDate arrival = LocalDate.now().plusDays(26);
        UUID request = UUID.randomUUID();
        var stale = new ListedStay(request, roomType, arrival, arrival.plusDays(2), 1, 2, new BigDecimal("1799"));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> bookingService.startListed(1L, stale)).getStatus());
        var stay = new ListedStay(request, roomType, arrival, arrival.plusDays(2), 1, 2, new BigDecimal("1800"));
        var pending = bookingService.startListed(1L, stay);
        assertEquals(0, new BigDecimal("3600").compareTo(pending.totalAmount()));
        assertNull(pending.hotelAddress());
        assertEquals(pending.bookingId(), bookingService.startListed(1L, stay).bookingId());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> bookingService.startListed(1L, stale)).getStatus());
        assertNull(jdbc.queryForObject("select bid_request_id from bookings where id = ?", Long.class, pending.bookingId()));
        mvc.perform(get("/api/v1/bookings/{id}", pending.bookingId()).header("X-User-Id", 2))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/bookings/direct").header("X-User-Id", 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].bookingId").value(pending.bookingId()));
        var paid = bookingService.confirmPayment(pending.bookingId(), "listed_demo_it");
        assertEquals(BookingStatus.CONFIRMED, paid.status());
        assertNotNull(paid.hotelAddress());
        assertEquals(BookingStatus.CANCELLED, bookingService.cancel(1L, pending.bookingId()).status());
    }

    @Test
    void destinationSuggestionsAreCaseInsensitiveAndOnlyExposeActiveCities() throws Exception {
        jdbc.update("""
                insert into hotels (name, city, area, address, status) values
                    ('City QA Central', 'City QA', 'Downtown', 'Test address', 'ACTIVE'),
                    ('City QA East', 'city qa', 'East', 'Test address', 'ACTIVE'),
                    ('City QA Private', 'City QA Hidden', 'East', 'Test address', 'PENDING_VERIFICATION')
                """);
        String url = "/api/v1/hotels/destinations";
        mvc.perform(get(url).param("prefix", "  CITY QA  ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]", equalToIgnoringCase("City QA")));
        mvc.perform(get(url).param("prefix", "City%")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(url).param("prefix", "City QA").param("hourly", "true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void cityRegionNeighbourhoodAndMapFiltersRespectAvailabilityForBothStayTypes() throws Exception {
        long hotelId = jdbc.queryForObject("""
                insert into hotels (name, city, area, address, status, latitude, longitude)
                values ('NCR QA Hotel', 'Gurgaon', 'Sector 44 QA', 'Test address', 'ACTIVE', 28.4638, 77.0717) returning id
                """, Long.class);
        long room = jdbc.queryForObject("""
                insert into room_types (hotel_id, category, name, max_guests, listed_price, min_price, total_rooms)
                values (?, 'STANDARD', 'Standard', 2, 2100, 1400, 2) returning id
                """, Long.class, hotelId);
        LocalDate date = LocalDate.now().plusDays(17);
        inventory.ensureRange(room, date, date.plusDays(2), 2);
        jdbc.update("insert into hotel_stay_policies (hotel_id, check_in_from, check_out_by) values (?, time '14:00', time '11:00')", hotelId);
        jdbc.update("insert into hourly_stay_rates values (?, true, 800, 1400)", room);

        mvc.perform(get("/api/v1/hotels/places").param("prefix", "sector").param("hourly", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].label").value("Sector 44 QA, Gurugram"))
                .andExpect(jsonPath("$[0].kind").value("NEIGHBOURHOOD"));
        mvc.perform(get("/api/v1/hotels/places").param("prefix", "delhi ncr"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].kind").value("REGION"));
        mvc.perform(get("/api/v1/hotels/search").param("city", "Delhi").param("area", "Sector 44 QA")
                        .param("checkIn", date.toString()).param("checkOut", date.plusDays(1).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hotels.length()").value(0));
        mvc.perform(get("/api/v1/hotels/search").param("city", "Delhi NCR").param("area", "Sector 44 QA")
                        .param("checkIn", date.toString()).param("checkOut", date.plusDays(1).toString())
                        .param("south", "28.40").param("north", "28.50").param("west", "77.00").param("east", "77.10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hotels[0].id").value(hotelId))
                .andExpect(jsonPath("$.hotels[0].latitude").value(28.46));
        mvc.perform(get("/api/v1/hotels/hourly").param("city", "Delhi NCR").param("date", date.toString())
                        .param("arrival", "12:00").param("hours", "3").param("south", "28.60")
                        .param("north", "28.70").param("west", "77.00").param("east", "77.30"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hotels[?(@.hotelId == " + hotelId + ")]").isEmpty());
        mvc.perform(get("/api/v1/hotels/hourly").param("city", "Gurugram").param("date", date.toString())
                        .param("arrival", "12:00").param("hours", "6").param("latitude", "28.46").param("longitude", "77.07"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hotels[0].hotelId").value(hotelId));
        mvc.perform(get("/api/v1/hotels/hourly").param("city", "Gurgaon").param("date", date.toString())
                        .param("arrival", "12:00").param("hours", "3").param("south", "28.4"))
                .andExpect(status().isUnprocessableEntity());

        String pin = "/api/v1/partner/hotels/" + hotelId + "/map-pin";
        mvc.perform(get(pin).header("X-User-Id", 3)).andExpect(status().isForbidden());
        mvc.perform(put(pin).header("X-User-Id", 4).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":28.46123,\"longitude\":77.07987}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.latitude").value(28.46123));
    }

    @Test
    void hourlyAndOvernightCheckoutsCompeteForSameCapacity() throws Exception {
        Long hotelId = jdbc.queryForObject("""
                insert into hotels (name, city, area, address, status)
                values ('Hourly QA Hotel', 'Hourly QA', 'Downtown', 'Test address', 'ACTIVE') returning id
                """, Long.class);
        Long roomType = jdbc.queryForObject("""
                insert into room_types (hotel_id, category, name, max_guests, listed_price, min_price, total_rooms)
                values (?, 'STANDARD', 'Standard', 2, 1800, 1200, 1) returning id
                """, Long.class, hotelId);
        LocalDate date = LocalDate.now().plusDays(6);
        inventory.ensureRange(roomType, date, date.plusDays(3), 1);
        jdbc.update("insert into hotel_stay_policies(hotel_id, check_in_from, check_out_by) values (?, time '14:00', time '11:00')", hotelId);
        String hourlyUrl = "/api/v1/partner/hotels/" + hotelId + "/hourly/" + roomType;
        String config = """
                {"enabled":true,"threeHours":700,"sixHours":1200}
                """;
        mvc.perform(put(hourlyUrl).header("X-User-Id", 3).contentType(MediaType.APPLICATION_JSON).content(config))
                .andExpect(status().isForbidden());
        mvc.perform(put(hourlyUrl).header("X-User-Id", 4).contentType(MediaType.APPLICATION_JSON).content(config))
                .andExpect(status().isNoContent());
        String destinationsUrl = "/api/v1/hotels/destinations";
        mvc.perform(get(destinationsUrl).param("prefix", "hour").param("hourly", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0]").value("Hourly QA"));
        String detailsUrl = "/api/v1/hotels/" + hotelId;
        mvc.perform(get(detailsUrl)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms[0].hourlyRates.threeHours").value(700))
                .andExpect(jsonPath("$.rooms[0].hourlyRates.sixHours").value(1200))
                .andExpect(jsonPath("$.rooms[0].minPrice").doesNotExist());

        var morning = bookingService.startHourly(1L, new HourlyStay(UUID.randomUUID(), roomType, date,
                LocalTime.of(8, 0), 3, 1, 2, new BigDecimal("700")));
        assertEquals(3, morning.shortStay().hours());
        assertEquals(0, new BigDecimal("700").compareTo(morning.totalAmount()));
        assertTrue(hourly.search("Hourly QA", date, LocalTime.of(11, 0), 3, RoomCategory.STANDARD, 1, 2, 0).hotels().isEmpty());
        assertEquals(1, hourly.search("Hourly QA", date, LocalTime.of(11, 30), 3, RoomCategory.STANDARD, 1, 2, 0).hotels().size());
        assertThrows(ApiException.class, () -> inventory.setTotal(roomType, date, date.plusDays(1), 0));

        var noon = new HourlyStay(UUID.randomUUID(), roomType, date, LocalTime.of(12, 0), 6, 1, 2, new BigDecimal("1200"));
        var overnight = new ListedStay(UUID.randomUUID(), roomType, date, date.plusDays(1), 1, 2, new BigDecimal("1800"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var dayUse = executor.submit(() -> attempt(start, () -> bookingService.startHourly(1L, noon)));
            var nightUse = executor.submit(() -> attempt(start, () -> bookingService.startListed(2L, overnight)));
            start.countDown();
            boolean hourlyWon = dayUse.get(10, TimeUnit.SECONDS);
            assertNotEquals(hourlyWon, nightUse.get(10, TimeUnit.SECONDS));
            BookingView winner = hourlyWon ? bookingService.startHourly(1L, noon) : bookingService.startListed(2L, overnight);
            long owner = hourlyWon ? 1L : 2L;
            assertEquals(BookingStatus.CONFIRMED, bookingService.confirmPayment(winner.bookingId(), "shared_demo_it").status());
            bookingService.cancel(owner, winner.bookingId());
            assertEquals(1, hourly.search("Hourly QA", date, LocalTime.of(12, 0), 6, RoomCategory.STANDARD, 1, 2, 0).hotels().size());
        }
        mvc.perform(put(hourlyUrl).header("X-User-Id", 4).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNoContent());
        mvc.perform(get(detailsUrl)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms[0].hourlyRates").doesNotExist());
        mvc.perform(get(destinationsUrl).param("prefix", "hour").param("hourly", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void withdrawalAndCheckoutCannotBothWin() throws Exception {
        BidView bid = bidService.create(1L, bid(24, 1, "1300"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var checkout = executor.submit(() -> attempt(start,
                    () -> bidService.acceptOffer(1L, bid.bidId(), bid.offers().getFirst().offerId())));
            var withdraw = executor.submit(() -> attempt(start, () -> bidService.cancel(1L, bid.bidId())));
            start.countDown();
            boolean held = checkout.get(10, TimeUnit.SECONDS);
            assertNotEquals(held, withdraw.get(10, TimeUnit.SECONDS));
            var result = bidService.get(1L, bid.bidId());
            assertEquals(held ? BidStatus.HELD : BidStatus.CANCELLED, result.status());
            assertEquals(held, result.bookingId() != null);
        }
    }

    private boolean attempt(CountDownLatch start, Runnable action) throws InterruptedException {
        start.await();
        try {
            action.run();
            return true;
        } catch (ApiException conflict) {
            assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
            return false;
        }
    }

    @Test
    void raisingPriceCrossesTheMinimumAndRoundsAreLimited() {
        BidView v = bidService.create(1L, bid(15, 1, "900"));
        assertEquals(OfferStatus.PENDING, v.offers().get(0).status());

        v = bidService.raise(1L, v.bidId(), new BigDecimal("1250"));      // round 2, now >= 1200
        assertEquals(OfferStatus.ACCEPTED, v.offers().get(0).status());

        v = bidService.raise(1L, v.bidId(), new BigDecimal("1300"));      // round 3
        Long bidId = v.bidId();
        ApiException ex = assertThrows(ApiException.class, () -> bidService.raise(1L, bidId, new BigDecimal("1400")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    void sameRoomsCannotBeSoldTwice() {
        // 6 + 6 rooms against a 10-room inventory: both bids look fine, only one can be held.
        BidView a = bidService.create(1L, bid(30, 6, "1300"));
        BidView b = bidService.create(2L, bid(30, 6, "1300"));
        assertEquals(OfferStatus.ACCEPTED, a.offers().get(0).status());
        assertEquals(OfferStatus.ACCEPTED, b.offers().get(0).status());

        bidService.acceptOffer(1L, a.bidId(), a.offers().get(0).offerId());

        ApiException ex = assertThrows(ApiException.class,
                () -> bidService.acceptOffer(2L, b.bidId(), b.offers().get(0).offerId()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void suspendedHotelCannotReplyOrReceiveBookingsFromExistingBids() {
        BidView accepted = bidService.create(1L, bid(45, 1, "1300"));
        BidView pending = bidService.create(1L, bid(48, 1, "1100"));
        var hotel = hotels.findById(sunrise()).orElseThrow();
        hotel.setStatus(HotelStatus.SUSPENDED);
        hotels.save(hotel);
        try {
            ApiException bookingError = assertThrows(ApiException.class, () ->
                    bidService.acceptOffer(1L, accepted.bidId(), accepted.offers().get(0).offerId()));
            assertEquals(HttpStatus.CONFLICT, bookingError.getStatus());

            ApiException replyError = assertThrows(ApiException.class, () ->
                    bidService.respond(hotel.getId(), pending.offers().get(0).offerId(),
                            new HotelRespond(HotelAction.ACCEPT, null, 1)));
            assertEquals(HttpStatus.CONFLICT, replyError.getStatus());

            BidView raised = bidService.raise(1L, pending.bidId(), new BigDecimal("1500"));
            assertEquals(OfferStatus.UNAVAILABLE, raised.offers().get(0).status());
        } finally {
            hotel.setStatus(HotelStatus.ACTIVE);
            hotels.save(hotel);
        }
    }

    @Test
    void hotelCannotAnswerAnotherHotelsOffer() {
        BidView v = bidService.create(1L, bid(40, 1, "1000"));
        Long offerId = v.offers().get(0).offerId();
        Long otherHotel = hotels.findByName("Hotel Metro Inn").orElseThrow().getId();
        assertThrows(ApiException.class,
                () -> bidService.respond(otherHotel, offerId, new HotelRespond(HotelAction.ACCEPT, null, 1)));
    }
}
