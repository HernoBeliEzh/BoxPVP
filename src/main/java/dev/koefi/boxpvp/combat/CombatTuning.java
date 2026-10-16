package dev.koefi.boxpvp.combat;

public record CombatTuning(int reaction, float aimError, float turnSpeed, int criticalChance, int strafe, int shieldChance, int healHealth, int charge, boolean followPearls) {
    public CombatTuning {
        if (reaction < 0 || reaction > 1200 || !Float.isFinite(aimError) || aimError < 0 || aimError > 45
            || !Float.isFinite(turnSpeed) || turnSpeed < 0.1f || turnSpeed > 90 || criticalChance < 0 || criticalChance > 100
            || strafe < 0 || strafe > 100 || shieldChance < 0 || shieldChance > 100 || healHealth < 2 || healHealth > 20 || charge < 10 || charge > 100) {
            throw new IllegalArgumentException("Настройки ИИ вне допустимого диапазона");
        }
    }

    public static CombatTuning of(DifficultyProfile difficulty) {
        int level = difficulty.ordinal();
        return new CombatTuning(difficulty.reaction, difficulty.error, difficulty.turnSpeed, Math.min(100, 20 + level * 20),
            Math.min(100, 15 + level * 20), Math.min(100, 15 + level * 20), Math.min(12, 8 + level * 2), 100, true);
    }

    public CombatTuning adjust(int field, int direction) {
        return new CombatTuning(
            field == 0 ? Math.clamp(reaction + direction, 0, 1200) : reaction,
            field == 1 ? Math.clamp(aimError + direction * 0.25f, 0, 45) : aimError,
            field == 2 ? Math.clamp(turnSpeed + direction * 4, 0.1f, 90) : turnSpeed,
            field == 3 ? Math.clamp(criticalChance + direction * 5, 0, 100) : criticalChance,
            field == 4 ? Math.clamp(strafe + direction * 5, 0, 100) : strafe,
            field == 5 ? Math.clamp(shieldChance + direction * 5, 0, 100) : shieldChance,
            field == 6 ? Math.clamp(healHealth + direction, 2, 20) : healHealth,
            field == 7 ? Math.clamp(charge + direction, 10, 100) : charge,
            field == 8 ? !followPearls : followPearls
        );
    }

    public CombatTuning withValue(int field, String text) {
        double number;
        try {
            number = Double.parseDouble(text.trim().replace(',', '.'));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Введи число или «отмена»");
        }
        if (!Double.isFinite(number) || field < 0 || field > 7 || number < 0 || number > 60000) {
            throw new IllegalArgumentException("Значение вне допустимого диапазона");
        }
        if (field >= 3 && number != Math.rint(number)) {
            throw new IllegalArgumentException("Для этой настройки нужно целое число");
        }
        return new CombatTuning(field == 0 ? (int) Math.ceil(number / 50) : reaction,
            field == 1 ? (float) number : aimError, field == 2 ? (float) number : turnSpeed,
            field == 3 ? (int) number : criticalChance, field == 4 ? (int) number : strafe,
            field == 5 ? (int) number : shieldChance, field == 6 ? (int) number : healHealth,
            field == 7 ? (int) number : charge, followPearls);
    }

    public static String range(int field) {
        return switch (field) {
            case 0 -> "0–60000 мс; шаг сервера — 50 мс";
            case 1 -> "0–45 градусов";
            case 2 -> "0.1–90 градусов за тик";
            case 6 -> "2–20 HP";
            case 7 -> "10–100 процентов";
            default -> "0–100 процентов";
        };
    }

    public String value(int field) {
        return switch (field) {
            case 0 -> reaction * 50 + " мс";
            case 1 -> String.format(java.util.Locale.ROOT, "%.2f°", aimError);
            case 2 -> turnSpeed + "°/тик";
            case 3 -> criticalChance + "%";
            case 4 -> strafe + "%";
            case 5 -> shieldChance + "%";
            case 6 -> healHealth / 2.0 + " ♥";
            case 7 -> charge + "%";
            default -> followPearls ? "Включено" : "Выключено";
        };
    }
}
