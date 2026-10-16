package dev.koefi.boxpvp.combat;

public enum CombatRule {
    NORMAL("Обычный бой"), CRITICAL("Только криты"), COMBO("Без критов");

    public final String title;

    CombatRule(String title) {
        this.title = title;
    }

    public boolean accepts(boolean critical) {
        return this == NORMAL || (this == CRITICAL) == critical;
    }
}
