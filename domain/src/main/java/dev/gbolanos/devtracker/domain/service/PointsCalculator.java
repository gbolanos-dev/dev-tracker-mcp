package dev.gbolanos.devtracker.domain.service;

import dev.gbolanos.devtracker.domain.enums.CycleOrigin;
import dev.gbolanos.devtracker.domain.model.CycleMetrics;
import dev.gbolanos.devtracker.domain.model.Ticket;

import java.util.List;

public final class PointsCalculator {

    private PointsCalculator() {}

    public static CycleMetrics calculate(List<Ticket> tickets, int reviewsPerformed) {
        int totalPoints = 0;
        int completedPoints = 0;
        int kickbackedPoints = 0;
        int completedPointsNew = 0;
        int completedPointsRolledOver = 0;
        int newTickets = 0;
        int rolledOverTickets = 0;

        for (Ticket ticket : tickets) {
            int points = ticket.devPoints();
            boolean isNew = ticket.cycleOrigin() == CycleOrigin.NEW;

            if (isNew) {
                newTickets++;
            } else {
                rolledOverTickets++;
            }

            totalPoints += points;

            if (ticket.state().isCompleted()) {
                completedPoints += points;
                if (isNew) {
                    completedPointsNew += points;
                } else {
                    completedPointsRolledOver += points;
                }
            }

            if (ticket.state().isKickbacked()) {
                kickbackedPoints += points;
            }
        }

        return new CycleMetrics(
                tickets.size(),
                newTickets,
                rolledOverTickets,
                totalPoints,
                completedPoints,
                kickbackedPoints,
                completedPointsNew,
                completedPointsRolledOver,
                reviewsPerformed
        );
    }
}
