package com.marketchat.commerce.catalog.domain.model;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Weekly opening slots in the merchant's local time. A slot whose closing time is before its opening time
 * runs past midnight (e.g. Friday 11:00 -> 02:00 also covers Saturday 00:00-02:00).
 */
public record OpeningHours(List<Slot> slots) {

    public record Slot(DayOfWeek day, LocalTime opens, LocalTime closes) {
        boolean pastMidnight() {
            return closes.isBefore(opens);
        }
    }

    public OpeningHours {
        slots = List.copyOf(slots);
    }

    public boolean isOpenAt(ZonedDateTime at) {
        DayOfWeek today = at.getDayOfWeek();
        LocalTime time = at.toLocalTime();
        for (Slot s : slots) {
            if (s.day() == today) {
                boolean afterOpen = !time.isBefore(s.opens());
                if (s.pastMidnight() ? afterOpen : afterOpen && time.isBefore(s.closes())) {
                    return true;
                }
            }
            // a slot started yesterday that runs past midnight
            if (s.pastMidnight() && s.day() == today.minus(1) && time.isBefore(s.closes())) {
                return true;
            }
        }
        return false;
    }
}
