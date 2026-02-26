package dev.gbolanos.devtracker.application;

import dev.gbolanos.devtracker.domain.model.Ticket;

import java.time.LocalDate;
import java.util.List;

public record TicketDetailsResult(List<Ticket> tickets, LocalDate resolvedStartDate, LocalDate resolvedEndDate) {
}
