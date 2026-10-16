package dev.koefi.boxpvp.combat;

public enum DifficultyProfile {
    EASY("Лёгкая", 12, 6, 10), NORMAL("Обычная", 6, 3, 18), HARD("Сложная", 3, 1.2f, 30), EXPERT("Эксперт", 1, 0.3f, 45), LEGEND("Легенда", 1, 0.05f, 60), NIGHTMARE("Кошмар", 0, 0, 90);

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
