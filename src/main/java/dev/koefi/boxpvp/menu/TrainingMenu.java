package dev.koefi.boxpvp.menu;

import com.google.gson.JsonObject;
import dev.koefi.boxpvp.combat.CombatRule;
import dev.koefi.boxpvp.combat.CombatTuning;
import dev.koefi.boxpvp.combat.DifficultyProfile;
import dev.koefi.boxpvp.combat.TrainingService;
import dev.koefi.boxpvp.kit.KitDefinition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

public final class TrainingMenu extends ChestMenu {
    private final TrainingService service;
    private final ServerPlayer owner;
    private final MenuState state;
    private final Map<Integer, Runnable> actions = new HashMap<>();

    public TrainingMenu(int syncId, Inventory inventory, TrainingService service, MenuState state) {
        super(MenuType.GENERIC_9x6, syncId, inventory, new SimpleContainer(54), 6);
        this.service = service;
        this.owner = (ServerPlayer) inventory.player;
        this.state = state;
        draw();
    }

    public void draw() {
        actions.clear();
        for (int slot = 0; slot < 54; slot++) {
            getContainer().setItem(slot, icon(Items.GRAY_STAINED_GLASS_PANE, " ", false));
        }
        var match = TrainingService.session(owner);
        if (service.preparing(owner)) {
            put(22, Items.CLOCK, "Подготовка арены: " + service.preparationPercent() + "%", false, () -> {}, "Бой начнётся автоматически", "Исходные вещи пока остаются у тебя");
            put(31, Items.BARRIER, "Отменить", false, () -> send("leave", json -> {}));
        } else if (match != null) {
            control();
        } else {
            switch (state.page) {
                case "kits" -> kits();
                case "advanced" -> advanced();
                default -> settings();
            }
        }
        if (!state.message.isEmpty()) {
            put(4, Items.PAPER, state.message, false, () -> {}, "Сообщение тренажёра");
        }
        broadcastChanges();
    }

    private ItemStack icon(Item item, String title, boolean selected, String... description) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(title).withStyle(ChatFormatting.AQUA).withStyle(style -> style.withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(java.util.Arrays.stream(description).map(text -> (Component) Component.literal(text).withStyle(ChatFormatting.GRAY).withStyle(style -> style.withItalic(false))).toList()));
        if (selected) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return stack;
    }

    private void put(int slot, Item item, String title, boolean selected, Runnable action, String... description) {
        getContainer().setItem(slot, icon(item, title, selected, description));
        actions.put(slot, action);
    }

    private void settings() {
        Item[] icons = {Items.WOODEN_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD, Items.NETHER_STAR, Items.WITHER_SKELETON_SKULL};
        for (DifficultyProfile profile : DifficultyProfile.values()) {
            put(11 + profile.ordinal(), icons[profile.ordinal()], profile.title, state.difficulty == profile, () -> {
                state.difficulty = profile;
                state.tuning = dev.koefi.boxpvp.combat.CombatTuning.of(profile);
                draw();
            }, "Реакция: " + profile.reaction * 50 + " мс", "Погрешность: " + profile.error + "°", "Нажми, чтобы выбрать", "Здоровье и урон остаются ванильными");
        }
        put(28, Items.RED_DYE, "Убрать бота", false, () -> {
            state.count = Math.max(1, state.count - 1);
            draw();
        });
        put(31, Items.PLAYER_HEAD, "Ботов: " + state.count, false, () -> {}, "От 1 до 8 противников одновременно");
        getContainer().getItem(31).setCount(state.count);
        put(34, Items.LIME_DYE, "Добавить бота", false, () -> {
            state.count = Math.min(8, state.count + 1);
            draw();
        });
        put(40, Items.COMPARATOR, "Точная настройка ИИ", false, () -> {
            state.page = "advanced";
            draw();
        }, "Реакция, прицеливание, криты, стрейф", "Щит, лечение и преследование перлов");
        put(49, Items.CHEST, "Далее: выбрать набор", false, () -> {
            state.page = "kits";
            draw();
        });
    }

    private void kits() {
        try {
            put(8, Items.RECOVERY_COMPASS, "Автоповтор: " + (state.autoReplay ? "Включён" : "Выключен"), state.autoReplay, () -> {
                state.autoReplay = !state.autoReplay;
                draw();
            }, "Новый бой через 5 секунд после результата", "Остановить: /bot leave или «Вернуться в мир»");
            List<KitDefinition> all = service.kits(owner).all();
            state.kitPage = Math.clamp(state.kitPage, 0, Math.max(0, (all.size() - 1) / 7));
            for (int index = 0; index < 7; index++) {
                int kitIndex = state.kitPage * 7 + index;
                if (kitIndex >= all.size()) {
                    break;
                }
                KitDefinition kit = all.get(kitIndex);
                Item symbol = kit.custom() ? Items.SHULKER_BOX : switch (kit.id()) {
                    case "axe" -> Items.DIAMOND_AXE;
                    case "mace" -> Items.MACE;
                    case "critical" -> Items.RABBIT_FOOT;
                    case "combo" -> Items.FEATHER;
                    default -> Items.NETHERITE_SWORD;
                };
                put(10 + index, symbol, kit.name(), kit.id().equals(state.kit), () -> {
                    state.kit = kit.id();
                    state.rule = kit.rule();
                    state.previewPage = 0;
                    state.protection = kit.custom() ? -1 : kit.id().equals("axe") ? 2 : 3;
                    draw();
                }, kit.rule().title, "Нажми для выбора и просмотра состава");
            }
            KitDefinition selected = service.kits(owner).get(state.kit);
            if (state.protection >= 0) {
                selected = service.kits(owner).withProtection(selected, state.protection);
            }
            KitDefinition chosen = selected;
            int[] preview = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
            List<ItemStack> contents = selected.items().stream().filter(stack -> !stack.isEmpty()).toList();
            int previewPages = Math.max(1, (contents.size() + preview.length - 1) / preview.length);
            state.previewPage %= previewPages;
            int index = 0;
            for (ItemStack stack : contents.subList(state.previewPage * preview.length, Math.min(contents.size(), (state.previewPage + 1) * preview.length))) {
                if (!stack.isEmpty() && index < preview.length) {
                    getContainer().setItem(preview[index++], stack.copy());
                }
            }
            put(45, Items.ARROW, "Предыдущие наборы", false, () -> {
                state.kitPage = Math.max(0, state.kitPage - 1);
                draw();
            });
            put(53, Items.ARROW, "Следующие наборы", false, () -> {
                state.kitPage = Math.min((all.size() - 1) / 7, state.kitPage + 1);
                draw();
            });
            put(46, Items.BOOK, "Сохранить мой инвентарь", false, () -> send("save", json -> json.addProperty("name", "Мой набор " + (all.size() - 4))), "Предметы, броня и вторая рука", "Имя можно задать: /bot kit save <название>");
            put(47, Items.REPEATER, "Правила: " + (selected.custom() ? state.rule.title : selected.rule().title), false, () -> {
                if (chosen.custom()) {
                    state.rule = CombatRule.values()[(state.rule.ordinal() + 1) % CombatRule.values().length];
                    draw();
                }
            }, selected.custom() ? "Нажми для смены правил" : "Правила определены пресетом");
            put(48, Items.BARRIER, selected.custom() ? "Удалить выбранный набор" : "Свои наборы можно удалять", false, () -> {
                if (chosen.custom()) {
                    send("delete", json -> json.addProperty("kit", chosen.id()));
                }
            });
            put(49, Items.LIME_CONCRETE, "Начать бой: " + selected.name(), true, () -> send("start", json -> {
                json.addProperty("kit", state.kit);
                json.addProperty("difficulty", state.difficulty.name());
                json.addProperty("count", state.count);
                json.addProperty("rule", state.rule.name());
                json.addProperty("protection", state.protection);
                json.addProperty("autoReplay", state.autoReplay);
                json.add("tuning", new com.google.gson.Gson().toJsonTree(state.tuning));
            }), "Сложность: " + state.difficulty.title, "Противников: " + state.count, "Обычные золотые яблоки");
            put(50, Items.COMPASS, "Назад к настройкам", false, () -> {
                state.page = "settings";
                draw();
            });
            put(51, Items.ENCHANTED_BOOK, state.protection < 0 ? "Зачарования своего набора" : "Защита " + state.protection + " → Острота " + Math.max(2, state.protection + 1), false, () -> {
                state.protection = state.protection >= 4 ? (chosen.custom() ? -1 : 0) : state.protection + 1;
                draw();
            }, "Нажми, чтобы изменить уровень защиты", "Острота подбирается выше защиты", "З0 → О2, З2 → О3, З3 → О4, З4 → О5", "Булава сохраняет свои зачарования");
            put(52, Items.PAPER, "Состав: " + (state.previewPage + 1) + "/" + previewPages, false, () -> {
                state.previewPage++;
                draw();
            }, "Нажми, чтобы увидеть остальные предметы");
        } catch (Exception exception) {
            state.message = exception.getMessage();
            state.kit = "op";
            state.page = "settings";
            settings();
        }
    }

    private void advanced() {
        String[] names = {"Реакция", "Ошибка прицела", "Скорость поворота", "Шанс крита", "Стрейф", "Использование щита", "Лечение при HP ниже", "Заряд удара"};
        String[] help = {"Меньше — быстрее реагирует", "Меньше — точнее попадает", "Выше — быстрее сопровождает цель", "В Критах всегда стремится к криту", "Выше — активнее двигается в стороны", "Шанс защиты при угрозе", "Порог начала лечения", "100% — полностью заряженные удары"};
        Item[] icons = {Items.CLOCK, Items.TARGET, Items.COMPASS, Items.RABBIT_FOOT, Items.FEATHER, Items.SHIELD, Items.GOLDEN_APPLE, Items.IRON_SWORD};
        for (int field = 0; field < 8; field++) {
            int selected = field;
            int slot = 10 + field / 2 * 9 + field % 2 * 4;
            put(slot, Items.RED_DYE, "− " + names[field], false, () -> {
                state.tuning = state.tuning.adjust(selected, -1);
                draw();
            });
            put(slot + 1, icons[field], names[field] + ": " + state.tuning.value(field), false, () -> service.requestSetting(owner, selected), help[field], "Нажми и введи точное значение в чат", CombatTuning.range(field));
            put(slot + 2, Items.LIME_DYE, "+ " + names[field], false, () -> {
                state.tuning = state.tuning.adjust(selected, 1);
                draw();
            });
        }
        put(47, Items.ENDER_PEARL, "Преследование перлами: " + state.tuning.value(8), state.tuning.followPearls(), () -> {
            state.tuning = state.tuning.adjust(8, 1);
            draw();
        }, "Ответ на бросок и преследование убегающей цели", "Лечение и спасение от падения имеют приоритет");
        put(49, Items.ARROW, "Назад", false, () -> {
            state.page = "settings";
            draw();
        });
    }

    private void control() {
        var match = TrainingService.session(owner);
        put(22, match.finished() ? Items.GOLD_INGOT : Items.CLOCK, match.finished() ? match.result() : "Бой приостановлен", true, () -> {}, "Длительность: " + match.seconds() + " сек.", "Осталось ботов: " + match.remaining());
        if (match.finished()) {
            put(30, Items.TOTEM_OF_UNDYING, "Повторить бой", false, () -> send("repeat", json -> {}));
        } else {
            put(30, Items.IRON_SWORD, "Продолжить бой", false, owner::closeContainer);
        }
        put(32, Items.OAK_DOOR, "Вернуться в мир", false, () -> send("leave", json -> {}), "Восстановить исходные вещи и состояние");
    }

    private void send(String action, java.util.function.Consumer<JsonObject> configure) {
        JsonObject json = new JsonObject();
        json.addProperty("action", action);
        configure.accept(json);
        service.action(owner, json.toString());
    }

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        if (player == owner && (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE) && slot >= 0 && slot < 54) {
            Runnable action = actions.get(slot);
            if (action != null) {
                state.message = "";
                action.run();
            }
        }
        sendAllDataToRemote();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, net.minecraft.world.inventory.Slot slot) {
        return false;
    }
}
