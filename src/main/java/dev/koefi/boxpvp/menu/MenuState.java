package dev.koefi.boxpvp.menu;

import dev.koefi.boxpvp.combat.CombatRule;
import dev.koefi.boxpvp.combat.CombatTuning;
import dev.koefi.boxpvp.combat.DifficultyProfile;

public final class MenuState {
    public String page = "settings";
    public DifficultyProfile difficulty = DifficultyProfile.NORMAL;
    public CombatTuning tuning = CombatTuning.of(difficulty);
    public CombatRule rule = CombatRule.NORMAL;
    public String kit = "op";
    public int count = 1;
    public int kitPage;
    public int previewPage;
    public int protection = 3;
    public boolean autoReplay;
    public String message = "";
}
