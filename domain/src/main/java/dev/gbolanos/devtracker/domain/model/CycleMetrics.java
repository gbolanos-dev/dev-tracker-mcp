package dev.gbolanos.devtracker.domain.model;

public record CycleMetrics(
        int totalTickets,
        int newTickets,
        int rolledOverTickets,
        int totalPoints,
        int completedPoints,
        int kickbackedPoints,
        int completedPointsNew,
        int completedPointsRolledOver,
        int reviewsPerformed
) {
}
