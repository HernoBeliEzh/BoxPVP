package dev.koefi.boxpvp.combat;

public enum DifficultyProfile {
    EASY("Лёгкая", 12, 6, 10), NORMAL("Обычная", 6, 3, 18), HARD("Сложная", 2, 0.7f, 40), EXPERT("Эксперт", 1, 0.15f, 60), LEGEND("Легенда", 0, 0.025f, 80), NIGHTMARE("Кошмар", 0, 0, 90);

    public final String title;
    public final int reaction;
    public final float error;
    public final float turnSpeed;

    DifficultyProfile(String title, int reaction, float error, float turnSpeed) {
        this.title = title;
        this.reaction = reaction;
        this.error = error;
        this.turnSpeed = turnSpeed;
    }
}
