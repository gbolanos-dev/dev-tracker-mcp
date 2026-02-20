package dev.gbolanos.devtracker.domain.model;

import java.time.LocalDate;

public record WorkCycle(LocalDate startDate, LocalDate endDate, String name) {
}
