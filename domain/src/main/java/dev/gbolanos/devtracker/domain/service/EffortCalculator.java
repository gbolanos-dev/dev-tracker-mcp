package dev.gbolanos.devtracker.domain.service;

import dev.gbolanos.devtracker.domain.enums.EffortLevel;

public final class EffortCalculator {

    private EffortCalculator() {}

    public static EffortLevel fromLinesChanged(int totalLines) {
        if (totalLines >= 1000) return EffortLevel.EXTRA_LARGE;
        if (totalLines >= 500) return EffortLevel.LARGE;
        if (totalLines >= 200) return EffortLevel.MEDIUM;
        if (totalLines >= 50) return EffortLevel.SMALL;
        return EffortLevel.TRIVIAL;
    }

    public static EffortLevel fromFilesChanged(int filesChanged) {
        if (filesChanged >= 21) return EffortLevel.EXTRA_LARGE;
        if (filesChanged >= 11) return EffortLevel.LARGE;
        if (filesChanged >= 6) return EffortLevel.MEDIUM;
        if (filesChanged >= 3) return EffortLevel.SMALL;
        return EffortLevel.TRIVIAL;
    }

    public static EffortLevel fromPullRequest(int linesAdded, int linesDeleted, int filesChanged) {
        EffortLevel byLines = fromLinesChanged(linesAdded + linesDeleted);
        EffortLevel byFiles = fromFilesChanged(filesChanged);
        return byLines.ordinal() >= byFiles.ordinal() ? byLines : byFiles;
    }
}
