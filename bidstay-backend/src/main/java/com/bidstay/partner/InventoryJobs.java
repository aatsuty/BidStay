package com.bidstay.partner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/** Publishes missing inventory for live hotels in bounded, idempotent batches. */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryJobs {
    static final int HORIZON_DAYS = 90;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Scheduled(initialDelay = 20_000, fixedDelay = 6 * 60 * 60 * 1000)
    public void rollForward() {
        LocalDate from = LocalDate.now(clock);
        long after = 0;
        long added = 0;
        while (true) {
            Batch batch = jdbc.queryForObject("""
                    with rooms as materialized (
                        select r.id, r.total_rooms from room_types r join hotels h on h.id = r.hotel_id
                        where h.status = 'ACTIVE' and r.id > ? order by r.id limit 150
                    ), inserted as (
                        insert into room_inventory (room_type_id, stay_date, total_rooms)
                        select r.id, day::date, r.total_rooms from rooms r
                        cross join generate_series(cast(? as date), cast(? as date), interval '1 day') day
                        where not exists(select 1 from room_inventory i where i.room_type_id = r.id and i.stay_date = day::date)
                        order by r.id, day
                        on conflict (room_type_id, stay_date) do nothing returning 1
                    )
                    select coalesce((select max(id) from rooms), 0), (select count(*) from inserted)
                    """, (rs, row) -> new Batch(rs.getLong(1), rs.getLong(2)), after, from, from.plusDays(HORIZON_DAYS - 1));
            if (batch == null || batch.lastRoomId() == 0) break;
            added += batch.added();
            after = batch.lastRoomId();
        }
        if (added > 0) log.info("Published {} new room-nights", added);
    }

    private record Batch(long lastRoomId, long added) {}
}
