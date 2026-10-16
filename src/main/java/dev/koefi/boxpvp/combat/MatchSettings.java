package dev.koefi.boxpvp.combat;

public record MatchSettings(DifficultyProfile difficulty, int count, String kit, CombatRule rule, CombatTuning tuning, boolean autoReplay) {
    public MatchSettings(DifficultyProfile difficulty, int count, String kit, CombatRule rule) {
        this(difficulty, count, kit, rule, CombatTuning.of(difficulty), false);
    }

    public MatchSettings(DifficultyProfile difficulty, int count, String kit, CombatRule rule, CombatTuning tuning) {
        this(difficulty, count, kit, rule, tuning, false);
    }

    public MatchSettings {
        if (difficulty == null || rule == null || tuning == null || count < 1 || count > 8 || kit == null || kit.length() > 64) {
            throw new IllegalArgumentException("Некорректные параметры боя");
        }
    }
}
