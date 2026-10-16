package dev.koefi.boxpvp.test;

import com.google.gson.JsonObject;
import dev.koefi.boxpvp.arena.Arena;
import dev.koefi.boxpvp.bot.BotPlayer;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.inventory.ClickType;
import dev.koefi.boxpvp.combat.MatchSession;
import dev.koefi.boxpvp.combat.TrainingService;
import dev.koefi.boxpvp.kit.KitRepository;
import dev.koefi.boxpvp.storage.PlayerSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

public final class TrainingIntegrationTest implements FabricClientGameTest {
    private final List<String> passed = new ArrayList<>();
    private ClientGameTestContext context;
    private TestServerContext server;

    @Override
    public void runTest(ClientGameTestContext context) {
        this.context = context;
        var world = context.worldBuilder().create();
        var save = world.getWorldSave();
        server = world.getServer();
        try {
            context.waitTicks(15);
            server.runOnServer(instance -> {
                ServerPlayer player = owner(instance);
                player.getInventory().setItem(0, new ItemStack(Items.NETHERITE_SPEAR));
                ItemStack helmet = new ItemStack(Items.DIAMOND_HELMET);
                helmet.setDamageValue(70);
                player.setItemSlot(EquipmentSlot.HEAD, helmet);
                player.giveExperiencePoints(37);
                player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 6000));
                KitRepository kits = new KitRepository(player);
                kits.saveCurrent("Копьё: тест");
                KitRepository restored = new KitRepository(player);
                var custom = restored.all().getLast();
                require(custom.items().getFirst().is(Items.NETHERITE_SPEAR), "Saved spear was lost");
                require(custom.items().get(39).getDamageValue() == 70, "Saved armor durability was lost");
                var op = kits.get("op");
                var enchants = player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
                require(EnchantmentHelper.getItemEnchantmentLevel(enchants.getOrThrow(Enchantments.SHARPNESS), op.items().getFirst()) == 4, "OP sharpness is not IV");
                require(EnchantmentHelper.getItemEnchantmentLevel(enchants.getOrThrow(Enchantments.PROTECTION), op.items().get(39)) == 3, "OP protection is not III");
                var unprotected = kits.withProtection(op, 0);
                require(EnchantmentHelper.getItemEnchantmentLevel(enchants.getOrThrow(Enchantments.PROTECTION), unprotected.items().get(39)) == 0, "Protection-zero option failed");
                require(EnchantmentHelper.getItemEnchantmentLevel(enchants.getOrThrow(Enchantments.SHARPNESS), unprotected.items().getFirst()) == 2, "Protection-zero must use Sharpness II");
                require(op.items().get(1).is(Items.GOLDEN_APPLE), "OP still has enchanted apples");
                op.items().getFirst().setDamageValue(50);
                require(kits.get("critical").items().getFirst().getDamageValue() == 0, "Preset stacks are shared");
            });
            pass("Custom kit persistence, spear, durability, independent stacks, OP Sharpness IV / Protection III");
            if (System.getProperty("boxpvp.test.focus", "all").equals("showcase")) {
                captureShowcase();
                return;
            }
            if (System.getProperty("boxpvp.test.focus", "all").equals("menus")) {
                configureShowcase();
                captureMenus();
                return;
            }
            if (System.getProperty("boxpvp.test.focus", "all").equals("tempo")) {
                checkCombatTempo();
                checkShieldCriticals();
                checkMovementAndSword();
                Files.writeString(Path.of("tempo-results.txt"), String.join(System.lineSeparator(), passed));
                return;
            }
            if (System.getProperty("boxpvp.test.focus", "all").equals("arena")) {
                checkArenaBoundary();
                return;
            }
            if (System.getProperty("boxpvp.test.focus", "all").equals("legacy")) {
                server.runOnServer(instance -> {
                    ServerPlayer player = owner(instance);
                    Path root = instance.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
                    Files.move(root.resolve("boxpvp"), root.resolve("fabricbase"));
                    PlayerSnapshot.capture(player);
                    player.getInventory().clearContent();
                    player.totalExperience = 0;
                    player.removeAllEffects();
                    var key = net.minecraft.resources.ResourceKey.create(Registries.DIMENSION, net.minecraft.resources.Identifier.parse("fabricbase:arena"));
                    var legacy = instance.getLevel(key);
                    require(legacy != null, "Legacy dimension was not retained");
                    legacy.setBlock(new net.minecraft.core.BlockPos(0, 64, 0), net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState(), 2);
                    legacy.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(legacy, 0.5, 65, 0.5, new ItemStack(Items.DIAMOND)));
                    player.teleportTo(legacy, 0.5, 65, 0.5, Set.of(), 0, 0, false);
                    instance.getPlayerList().saveAll();
                });
                world.close();
                world = save.open();
                server = world.getServer();
                context.waitTicks(25);
                server.runOnServer(instance -> {
                    ServerPlayer player = owner(instance);
                    require(!Arena.inside(player) && player.getMainHandItem().is(Items.NETHERITE_SPEAR) && player.totalExperience == 37
                        && player.hasEffect(MobEffects.NIGHT_VISION) && PlayerSnapshot.pending(player) == null, "Legacy interrupted fight did not restore original state");
                    var key = net.minecraft.resources.ResourceKey.create(Registries.DIMENSION, net.minecraft.resources.Identifier.parse("fabricbase:arena"));
                    require(instance.getLevel(key).getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(-10, 0, -10, 10, 130, 10)).isEmpty(), "Legacy arena retained training drops after recovery");
                });
                pass("An interrupted FabricBase session loads its legacy dimension, restores original gear/XP/effects and removes legacy training drops");
                Files.writeString(Path.of("legacy-recovery-results.txt"), passed.getLast());
                return;
            }
            if (System.getProperty("boxpvp.test.focus", "all").equals("movement")) {
                checkMovementAndSword();
                return;
            }
            if (System.getProperty("boxpvp.test.focus", "all").equals("tactics")) {
                checkNewFeatures();
                Files.writeString(Path.of("integration-results.txt"), String.join(System.lineSeparator(), passed));
                return;
            }
            if (System.getProperty("boxpvp.test.focus", "all").equals("mace")) {
                checkMace();
                checkMovingMace();
                Files.writeString(Path.of("mace-results.txt"), String.join(System.lineSeparator(), passed));
                return;
            }
            if (System.getProperty("boxpvp.test.focus", "all").equals("pearls")) {
                checkPressurePearls();
                return;
            }
            server.runOnServer(instance -> instance.getCommands().performPrefixedCommand(owner(instance).createCommandSourceStack(), "bot"));
            context.waitForScreen(ContainerScreen.class);
            context.takeScreenshot("01-settings");
            clickSlot(40);
            context.takeScreenshot("02-advanced");
            clickSlot(49);
            clickSlot(15);
            clickSlot(49);
            context.takeScreenshot("02-kits");
            clickSlot(49);
            boolean prepared = false;
            for (int tick = 0; tick < 500; tick++) {
                if (server.computeOnServer(instance -> current(instance) != null && current(instance).active())) {
                    prepared = true;
                    break;
                }
                server.runOnServer(instance -> {
                    if (TrainingService.get(instance).preparing(owner(instance))) {
                        require(owner(instance).getMainHandItem().is(Items.NETHERITE_SPEAR), "Arena preparation changed inventory before teleport");
                        require(PlayerSnapshot.pending(owner(instance)) == null, "Arena preparation captured player too early");
                    }
                });
                context.waitTick();
            }
            require(prepared, "Incremental arena preparation did not finish");
            pass("Arena prepares incrementally before teleport and preserves inventory while queued");
            require(server.computeOnServer(instance -> current(instance) != null && current(instance).active()), "UI did not start a match");
            context.takeScreenshot("03-arena");
            pass("Chest settings, advanced AI settings, Legend difficulty, kit preview, countdown and arena startup");
            leaveAndCheck();
            for (String preset : List.of("op", "axe", "mace", "critical", "combo")) {
                start(preset, 1);
                server.runOnServer(instance -> {
                    MatchSession match = current(instance);
                    require(match.remaining() == 1, "Bot missing from " + preset);
                    for (int slot = 0; slot < match.kit.items().size(); slot++) {
                        require(ItemStack.matches(match.owner.getInventory().getItem(slot), match.bots.getFirst().getInventory().getItem(slot)), "Player and bot kit differ in " + preset + " slot " + slot);
                    }
                });
                pass("Preset " + preset + " gives equal equipment");
                leaveAndCheck();
            }
            for (String mode : List.of("critical", "combo")) {
                start(mode, 1);
                context.waitTicks(65);
                checkHit(mode, false);
                context.waitTicks(25);
                checkHit(mode, true);
                pass("Native attack damage and critical filtering: " + mode);
                leaveAndCheck();
            }
            start("op", 8);
            context.waitTicks(65);
            Vec3 initial = server.computeOnServer(instance -> current(instance).bots.getFirst().position());
            context.waitTicks(80);
            server.runOnServer(instance -> {
                MatchSession match = current(instance);
                require(match.remaining() == 8, "Eight-bot session lost bots unexpectedly");
                require(match.bots.getFirst().position().distanceToSqr(initial) > 1, "Bots are not moving");
                require(!TrainingService.allowDamage(match.bots.getFirst(), match.bots.getFirst().damageSources().playerAttack(match.bots.get(1))), "Friendly fire is enabled");
                match.bots.forEach(bot -> TrainingService.allowDeath(bot));
            });
            context.waitTicks(3);
            context.waitForScreen(ContainerScreen.class);
            require(server.computeOnServer(instance -> current(instance).result().equals("Победа!")), "Victory was not detected");
            context.takeScreenshot("04-victory");
            clickSlot(30);
            context.waitTicks(3);
            require(server.computeOnServer(instance -> current(instance).remaining() == 8 && !current(instance).finished()), "Rematch failed");
            pass("Eight bots move, friendly fire is disabled, victory and rematch work");
            leaveAndCheck();
            checkAutonomousCriticals();
            checkPearls();
            checkSupplies();
            checkPlayerWindCharge();
            checkMace();
            checkMovingMace();
            checkNewFeatures();
            server.runOnServer(instance -> {
                Path root = instance.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
                Files.move(root.resolve("boxpvp"), root.resolve("fabricbase"));
                require(new KitRepository(owner(instance)).all().stream().anyMatch(kit -> kit.custom() && kit.items().getFirst().is(Items.NETHERITE_SPEAR)), "Legacy data directory lost custom kits");
                require(PlayerSnapshot.path(owner(instance)).getParent().endsWith("fabricbase"), "Legacy recovery path was not preserved");
            });
            pass("Renamed BoxPVP reads legacy FabricBase kit and recovery directories");
            start("op", 1);
            context.waitTicks(65);
            world.close();
            world = save.open();
            server = world.getServer();
            context.waitTicks(20);
            server.runOnServer(instance -> {
                ServerPlayer player = owner(instance);
                require(current(instance) == null && !Arena.inside(player), "Live match was not closed on disconnect");
                require(player.getMainHandItem().is(Items.NETHERITE_SPEAR) && player.totalExperience == 37, "Live-match disconnect lost original state");
                require(PlayerSnapshot.pending(player) == null, "Live-match recovery did not commit");
            });
            pass("Closing and reopening an actual live match restores the original player without concurrent saves");
            server.runOnServer(instance -> {
                ServerPlayer player = owner(instance);
                PlayerSnapshot.capture(player);
                player.getInventory().clearContent();
                player.experienceLevel = 0;
                player.totalExperience = 0;
                player.removeAllEffects();
                player.teleportTo(Arena.prepare(instance), 0.5, 65, 0.5, Set.of(), 0, 0, false);
                instance.getPlayerList().saveAll();
            });
            world.close();
            world = save.open();
            server = world.getServer();
            context.waitTicks(20);
            server.runOnServer(instance -> {
                ServerPlayer player = owner(instance);
                require(!Arena.inside(player), "Recovery left player in arena");
                require(player.getMainHandItem().is(Items.NETHERITE_SPEAR), "Recovery lost original inventory");
                require(player.getItemBySlot(EquipmentSlot.HEAD).getDamageValue() == 70, "Recovery lost original durability");
                require(player.totalExperience == 37, "Recovery lost experience");
                require(player.hasEffect(MobEffects.NIGHT_VISION), "Recovery lost effects");
                require(PlayerSnapshot.pending(player) == null, "Recovery journal was not removed");
            });
            pass("Interrupted-session journal survives world restart and restores inventory, durability, XP, effects and dimension");
            Files.writeString(Path.of("integration-results.txt"), String.join(System.lineSeparator(), passed));
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        } finally {
            world.close();
        }
    }

    private void start(String kit, int count) {
        start(kit, count, "LEGEND");
    }

    private void start(String kit, int count, String difficulty) {
        context.setScreen(() -> null);
        server.runOnServer(instance -> {
            JsonObject action = new JsonObject();
            action.addProperty("action", "start");
            action.addProperty("kit", kit);
            action.addProperty("count", count);
            action.addProperty("difficulty", difficulty);
            action.addProperty("rule", "NORMAL");
            TrainingService.get(instance).action(owner(instance), action.toString());
        });
        for (int tick = 0; tick < 500 && server.computeOnServer(instance -> current(instance) == null); tick++) {
            context.waitTick();
        }
        require(server.computeOnServer(instance -> current(instance) != null && !current(instance).finished()), "Could not start " + kit);
        context.waitTicks(2);
    }

    private void leaveAndCheck() throws java.io.IOException {
        server.runOnServer(instance -> TrainingService.get(instance).leave(owner(instance)));
        context.waitTicks(2);
        server.runOnServer(instance -> {
            ServerPlayer player = owner(instance);
            require(current(instance) == null, "Session was not removed");
            require(!Arena.inside(player), "Player was not returned to original dimension");
            require(player.getMainHandItem().is(Items.NETHERITE_SPEAR), "Original inventory was not restored");
            require(player.getItemBySlot(EquipmentSlot.HEAD).getDamageValue() == 70, "Original durability was not restored");
            require(player.totalExperience == 37, "Original experience was not restored");
            require(player.hasEffect(MobEffects.NIGHT_VISION), "Original effect was not restored");
            require(PlayerSnapshot.pending(player) == null, "Recovery journal survived successful exit");
        });
    }

    private void checkHit(String mode, boolean critical) {
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            ServerPlayer player = match.owner;
            BotPlayer bot = match.bots.getFirst();
            player.removeAllEffects();
            bot.removeAllEffects();
            player.setHealth(20);
            bot.setHealth(20);
            player.setAbsorptionAmount(0);
            bot.setAbsorptionAmount(0);
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                bot.setItemSlot(slot, ItemStack.EMPTY);
            }
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
            player.setSprinting(false);
            player.setOnGround(!critical);
            player.fallDistance = critical ? 0.5 : 0;
            bot.setDeltaMovement(Vec3.ZERO);
            bot.hurtMarked = false;
            player.attack(bot);
            boolean expected = mode.equals("critical") == critical;
            require((bot.getHealth() < 20) == expected, "Wrong native hit result for " + mode + ", critical=" + critical + ", health=" + bot.getHealth());
            require((bot.getDeltaMovement().horizontalDistanceSqr() > 0.0001) == expected, "Wrong server bot knockback for " + mode + ", critical=" + critical);
            player.setOnGround(true);
            player.fallDistance = 0;
        });
    }

    private void checkSupplies() throws java.io.IOException {
        start("op", 1);
        context.waitTicks(65);
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            match.owner.connection.teleport(0.5, 65, -30.5, 0, 0);
            BotPlayer bot = match.bots.getFirst();
            bot.stopUsingItem();
            bot.snapTo(0.5, 65, 20.5, 180, 0);
            bot.removeAllEffects();
            bot.setHealth(6);
        });
        context.waitTicks(45);
        require(server.computeOnServer(instance -> current(instance).bots.getFirst().hasEffect(MobEffects.REGENERATION)), "Bot did not eat enchanted apple");
        pass("Regular golden apple is consumed and applies vanilla regeneration");
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            BotPlayer bot = match.bots.getFirst();
            bot.setHealth(20);
            match.owner.connection.teleport(0.5, 65, -30.5, 0, 0);
            bot.snapTo(0.5, 65, 20.5, 180, 0);
        });
        context.waitTicks(80);
        require(server.computeOnServer(instance -> current(instance).bots.getFirst().hasEffect(MobEffects.STRENGTH) && current(instance).bots.getFirst().hasEffect(MobEffects.SPEED)), "Bot did not drink strength and speed potions");
        pass("Splash Strength II and Speed II are consumed and apply native effects");
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            BotPlayer bot = match.bots.getFirst();
            bot.stopUsingItem();
            bot.snapTo(0.5, 65, 20.5, 180, 0);
            match.owner.connection.teleport(0.5, 65, -30.5, 0, 0);
            ItemStack helmet = bot.getItemBySlot(EquipmentSlot.HEAD);
            helmet.setDamageValue((int) (helmet.getMaxDamage() * 0.8));
        });
        int damaged = server.computeOnServer(instance -> current(instance).bots.getFirst().getItemBySlot(EquipmentSlot.HEAD).getDamageValue());
        context.waitTicks(100);
        int repaired = server.computeOnServer(instance -> current(instance).bots.getFirst().getItemBySlot(EquipmentSlot.HEAD).getDamageValue());
        require(repaired < damaged, "Bot did not repair armor using XP: " + damaged + " -> " + repaired);
        pass("Experience bottles repair damaged Mending armor through native XP orbs");
        leaveAndCheck();
        start("axe", 1);
        context.waitTicks(65);
        boolean blocked = false;
        for (int tick = 0; tick < 60; tick++) {
            server.runOnServer(instance -> {
                MatchSession match = current(instance);
                BotPlayer bot = match.bots.getFirst();
                bot.snapTo(0.5, 65, 0.5, 0, 0);
                bot.resetAttackStrengthTicker();
                match.owner.connection.teleport(0.5, 65, 3, 180, 0);
                match.owner.setHealth(20);
            });
            context.waitTick();
            if (server.computeOnServer(instance -> current(instance).bots.getFirst().isBlocking())) {
                blocked = true;
                break;
            }
        }
        require(blocked, "Bot never raised its shield");
        pass("Axe bot uses its shield while facing a threat");
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            BotPlayer bot = match.bots.getFirst();
            bot.setYRot(0);
            match.owner.setSprinting(false);
            match.owner.attack(bot);
            require(bot.getCooldowns().isOnCooldown(bot.getOffhandItem()), "Native axe hit did not disable the shield");
        });
        pass("A charged native axe hit disables a blocking shield");
        leaveAndCheck();
    }

    private void checkMace() throws java.io.IOException {
        start("mace", 1, "NIGHTMARE");
        context.waitTicks(65);
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            match.owner.connection.teleport(0.5, 65, 3.5, 180, 0);
            match.owner.setAbsorptionAmount(200);
            BotPlayer bot = match.bots.getFirst();
            bot.snapTo(0.5, 65, 0.5, 0, 0);
        });
        double highest = 65;
        for (int tick = 0; tick < 100; tick++) {
            context.waitTick();
            highest = Math.max(highest, server.computeOnServer(instance -> current(instance).bots.getFirst().getY()));
            if (highest > 68) {
                break;
            }
        }
        require(highest > 68, "Mace bot failed to launch with wind charge: " + highest);
        pass("Mace bot launches itself using a real wind-charge projectile");
        boolean smash = false;
        for (int tick = 0; tick < 180; tick++) {
            context.waitTick();
            if (server.computeOnServer(instance -> current(instance).bots.getFirst().brain.smashHits > 0)) {
                smash = true;
                break;
            }
        }
        require(smash, server.computeOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            return "Mace did not hit before landing: y=" + bot.getY() + ", fall=" + bot.fallDistance + ", distance=" + bot.distanceTo(owner(instance));
        }));
        pass("Mace bot lands a real airborne smash with bonus damage before touching the floor");
        server.runOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            for (int slot = 0; slot < bot.getInventory().getContainerSize(); slot++) {
                if (bot.getInventory().getItem(slot).is(Items.WIND_CHARGE)) {
                    bot.getInventory().setItem(slot, ItemStack.EMPTY);
                }
            }
        });
        boolean chained = false;
        double chainPeak = server.computeOnServer(instance -> current(instance).bots.getFirst().getY());
        for (int tick = 0; tick < 220; tick++) {
            context.waitTick();
            chainPeak = Math.max(chainPeak, server.computeOnServer(instance -> current(instance).bots.getFirst().getY()));
            if (server.computeOnServer(instance -> current(instance).bots.getFirst().brain.smashHits >= 2)) {
                chained = true;
                break;
            }
        }
        String chainState = server.computeOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            return "Wind Burst chain failed: hits=" + bot.brain.smashHits + ", y=" + bot.getY() + ", fall=" + bot.fallDistance + ", distance=" + bot.distanceTo(owner(instance))
                + ", flights=" + bot.brain.flightsStarted + ", clutches=" + bot.brain.clutchPearls + ", hand=" + bot.getMainHandItem().getHoverName().getString();
        });
        require(chained, chainState + ", peak=" + chainPeak);
        pass("Mace bot chains another airborne smash using Wind Burst III without any remaining wind charges");
        server.runOnServer(instance -> TrainingService.allowDeath(owner(instance)));
        context.waitTicks(2);
        context.waitForScreen(ContainerScreen.class);
        require(server.computeOnServer(instance -> current(instance).result().equals("Поражение")), "Defeat was not detected");
        context.takeScreenshot("05-defeat");
        pass("Defeat opens results and suppresses normal death");
        leaveAndCheck();
    }

    private void checkMovingMace() throws java.io.IOException {
        start("mace", 1, "NIGHTMARE");
        context.waitTicks(65);
        double absorptionLimit = server.computeOnServer(instance -> owner(instance).getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_ABSORPTION).getBaseValue());
        server.runOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            bot.stopUsingItem();
            bot.snapTo(0.5, 65, 4.5, 180, 0);
            bot.setDeltaMovement(Vec3.ZERO);
            bot.brain.smashHits = 0;
            owner(instance).connection.teleport(-4.5, 65, -4.5, -90, 0);
            owner(instance).getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_ABSORPTION).setBaseValue(500);
            owner(instance).setAbsorptionAmount(500);
            require(owner(instance).getAbsorptionAmount() == 500, "Moving target lacks its test damage buffer");
        });
        List<String> trace = new ArrayList<>();
        trace.add("tick,x,y,z,vx,vy,vz,targetX,targetZ,charge,fall,health,weapon,smashes,flights,clutches");
        try {
            context.runOnClient(client -> {
                client.options.keyUp.setDown(true);
                client.options.keySprint.setDown(true);
            });
            for (int tick = 0; tick < 280; tick++) {
                if (tick % 35 == 0) {
                    float yaw = new float[]{-90, 0, 90, 180}[(tick / 35) % 4];
                    context.runOnClient(client -> client.player.setYRot(yaw));
                }
                context.waitTick();
                if (tick % 5 == 0) {
                    int elapsed = tick;
                    trace.add(server.computeOnServer(instance -> {
                        BotPlayer bot = current(instance).bots.getFirst();
                        Vec3 motion = bot.getDeltaMovement();
                        return String.format(java.util.Locale.ROOT, "%d,%.2f,%.2f,%.2f,%.3f,%.3f,%.3f,%.2f,%.2f,%.2f,%.2f,%.2f,%s,%d,%d,%d",
                            elapsed, bot.getX(), bot.getY(), bot.getZ(), motion.x, motion.y, motion.z, owner(instance).getX(), owner(instance).getZ(),
                            bot.getAttackStrengthScale(0.5f), bot.fallDistance, bot.getHealth(), bot.getMainHandItem().getItem(), bot.brain.smashHits, bot.brain.flightsStarted, bot.brain.clutchPearls);
                    }));
                }
            }
        } finally {
            context.runOnClient(client -> {
                client.options.keyUp.setDown(false);
                client.options.keySprint.setDown(false);
            });
            Files.write(Path.of("mace-moving-trace.csv"), trace);
            server.runOnServer(instance -> owner(instance).getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_ABSORPTION).setBaseValue(absorptionLimit));
        }
        int hits = server.computeOnServer(instance -> current(instance).bots.getFirst().brain.smashHits);
        require(hits >= 3 && server.computeOnServer(instance -> current(instance).active() && !current(instance).bots.getFirst().defeated),
            "Nightmare mace failed to pressure a player sprinting and changing directions: " + hits + " smashes in 280 ticks");
        pass("Nightmare mace tracks a sprinting player through direction changes and lands repeated native airborne smashes: " + hits);
        server.runOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            int charges = 0;
            for (int slot = 0; slot < bot.getInventory().getContainerSize(); slot++) {
                ItemStack stack = bot.getInventory().getItem(slot);
                if (stack.is(Items.WIND_CHARGE)) {
                    charges += stack.getCount();
                }
            }
            require(bot.brain.windBoosts >= 2 && charges + bot.brain.windBoosts == 64,
                "Nightmare rarely launched or did not consume real wind charges: boosts=" + bot.brain.windBoosts + ", remaining=" + charges);
        });
        pass("Nightmare repeatedly launches with wind charges while chasing and consumes exactly one finite charge per launch");
        leaveAndCheck();
    }

    private void checkPlayerWindCharge() throws java.io.IOException {
        start("mace", 1);
        context.waitTicks(65);
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            match.bots.getFirst().snapTo(0.5, 65, 30.5, 180, 0);
            match.owner.connection.teleport(0.5, 65, -25.5, 0, 90);
            require(Arena.useBlock(match.owner, net.minecraft.world.InteractionHand.MAIN_HAND) == net.minecraft.world.InteractionResult.PASS, "Mace blocks offhand wind charge fallback");
            require(Arena.useBlock(match.owner, net.minecraft.world.InteractionHand.OFF_HAND) == net.minecraft.world.InteractionResult.PASS, "Arena rejects wind charge use on floor");
        });
        context.waitTicks(3);
        context.runOnClient(client -> {
            var hit = new net.minecraft.world.phys.BlockHitResult(new Vec3(0.5, 65, -25.5), net.minecraft.core.Direction.UP, new net.minecraft.core.BlockPos(0, 64, -26), false);
            var result = client.gameMode.useItemOn(client.player, net.minecraft.world.InteractionHand.OFF_HAND, hit);
            require(result == net.minecraft.world.InteractionResult.PASS, "Client blocks floor-targeted wind charge fallback");
            require(client.gameMode.useItem(client.player, net.minecraft.world.InteractionHand.OFF_HAND).consumesAction(), "Client failed to throw wind charge");
        });
        double highest = 65;
        for (int tick = 0; tick < 25; tick++) {
            context.waitTick();
            highest = Math.max(highest, server.computeOnServer(instance -> owner(instance).getY()));
        }
        require(highest > 67, "Player wind charge failed to launch: " + highest);
        require(server.computeOnServer(instance -> owner(instance).getOffhandItem().getCount() == 63), "Player wind charge consumption is incorrect");
        server.runOnServer(instance -> {
            ServerPlayer player = owner(instance);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.SMOOTH_QUARTZ));
            require(Arena.useBlock(player, net.minecraft.world.InteractionHand.MAIN_HAND) == net.minecraft.world.InteractionResult.FAIL, "Arena allows block placement");
        });
        pass("Player can launch a floor-targeted offhand wind charge while holding a mace; arena block placement stays blocked");
        leaveAndCheck();
    }

    private ServerPlayer owner(MinecraftServer instance) {
        return instance.getPlayerList().getPlayers().getFirst();
    }

    private void clickSlot(int slot) {
        context.runOnClient(client -> client.gameMode.handleInventoryMouseClick(client.player.containerMenu.containerId, slot, 0, ClickType.PICKUP, client.player));
        context.waitTicks(2);
    }

    private void checkAutonomousCriticals() throws java.io.IOException {
        start("critical", 1);
        context.waitTicks(65);
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            match.owner.connection.teleport(0.5, 65, 2.8, 180, 0);
            BotPlayer bot = match.bots.getFirst();
            bot.stopUsingItem();
            bot.snapTo(0.5, 65, 0.5, 0, 0);
            for (int slot = 1; slot < 36; slot++) {
                bot.getInventory().setItem(slot, ItemStack.EMPTY);
            }
        });
        boolean hit = false;
        for (int tick = 0; tick < 60; tick++) {
            context.waitTick();
            if (server.computeOnServer(instance -> current(instance).bots.getFirst().brain.criticalHits > 0)) {
                hit = true;
                break;
            }
        }
        require(hit, server.computeOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            return "Autonomous critical failed: y=" + bot.getY() + " fall=" + bot.fallDistance + " ground=" + bot.onGround() + " range=" + bot.distanceTo(owner(instance));
        }));
        pass("Bot independently jumps, accumulates real fall distance and deals an accepted critical hit");
        context.takeScreenshot("06-critical-hit");
        java.util.UUID uuid = server.computeOnServer(instance -> {
            MatchSession match = current(instance);
            match.owner.connection.teleport(0.5, 65, -7, 0, -8);
            BotPlayer bot = match.bots.getFirst();
            bot.snapTo(0.5, 65, 0.5, 180, 0);
            bot.setHealth(12);
            return bot.getUUID();
        });
        context.waitTicks(2);
        require(context.computeOnClient(client -> client.level.getPlayerByUUID(uuid) != null && Math.abs(client.level.getPlayerByUUID(uuid).getHealth() - 12) < 0.1), "Bot health did not synchronize to client");
        context.takeScreenshot("07-bot-health");
        pass("Bot HP is synchronized to the client for live overhead labels");
        leaveAndCheck();
    }

    private void checkPearls() throws java.io.IOException {
        start("op", 1);
        context.waitTicks(75);
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            BotPlayer bot = match.bots.getFirst();
            require(bot.brain.pearlsThrown == 0, "Bot threw an unsolicited pearl");
            bot.stopUsingItem();
            bot.snapTo(0.5, 65, -6, 0, 0);
            for (int slot = 0; slot < bot.getInventory().getContainerSize(); slot++) {
                if (bot.getInventory().getItem(slot).is(Items.POTION) || bot.getInventory().getItem(slot).is(Items.SPLASH_POTION)) {
                    bot.getInventory().setItem(slot, ItemStack.EMPTY);
                }
            }
            match.owner.connection.teleport(0.5, 65, -10, 90, -10);
            match.owner.getInventory().setSelectedSlot(2);
            match.owner.gameMode.useItem(match.owner, match.arena, match.owner.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND);
        });
        context.waitTicks(65);
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            BotPlayer bot = match.bots.getFirst();
            require(bot.brain.pearlsThrown == 1, "Bot did not respond to player pearl: " + bot.brain.pearlsThrown);
            require(match.pearlLanding != null, "Player pearl was not observed");
            require(bot.getX() < -8, "Bot pearl did not follow the player's westward throw: " + bot.position());
        });
        pass("Bot keeps pearls while target is far and follows a player's throw with a real projectile");
        leaveAndCheck();
    }

    private MatchSession current(MinecraftServer instance) {
        return TrainingService.session(owner(instance));
    }

    private void checkNewFeatures() throws java.io.IOException {
        checkCombatTempo();
        checkChatSettingsAndReplay();
        checkRecoveryAndBusyPearls();
        checkPressurePearls();
        checkFallClutch();
        checkSpearAndFlight();
        checkShieldCriticals();
        checkMovementAndSword();
        checkArenaBoundary();
    }

    private void checkChatSettingsAndReplay() throws java.io.IOException {
        server.runOnServer(instance -> TrainingService.get(instance).open(owner(instance), ""));
        context.waitForScreen(ContainerScreen.class);
        if (server.computeOnServer(instance -> owner(instance).containerMenu.getSlot(50).getItem().is(Items.COMPASS))) {
            clickSlot(50);
        }
        clickSlot(40);
        clickSlot(11);
        context.runOnClient(client -> client.player.connection.sendChat("5000"));
        context.waitForScreen(ContainerScreen.class);
        require(server.computeOnServer(instance -> owner(instance).containerMenu.getSlot(11).getItem().getHoverName().getString().contains("5000")), "Chat reaction value was not applied");
        clickSlot(11);
        context.runOnClient(client -> client.player.connection.sendChat("70000"));
        context.waitTicks(3);
        require(!context.computeOnClient(client -> client.screen instanceof ContainerScreen), "Invalid chat value closed input");
        context.runOnClient(client -> client.player.connection.sendChat("0"));
        context.waitForScreen(ContainerScreen.class);
        require(server.computeOnServer(instance -> owner(instance).containerMenu.getSlot(11).getItem().getHoverName().getString().contains("0 мс")), "Zero reaction value was rejected");
        pass("Clicking a characteristic accepts exact chat values, supports 0–60000 ms and rejects out-of-range input");
        clickSlot(49);
        clickSlot(16);
        clickSlot(49);
        clickSlot(8);
        context.takeScreenshot("08-autoreplay-kit");
        clickSlot(49);
        for (int tick = 0; tick < 500 && server.computeOnServer(instance -> current(instance) == null); tick++) {
            context.waitTick();
        }
        require(server.computeOnServer(instance -> current(instance).settings.difficulty() == dev.koefi.boxpvp.combat.DifficultyProfile.NIGHTMARE
            && current(instance).settings.autoReplay() && current(instance).settings.tuning().reaction() == 0), "Nightmare or autoreplay UI setting was lost");
        server.runOnServer(instance -> current(instance).finish("Победа!"));
        context.waitTicks(105);
        require(server.computeOnServer(instance -> !current(instance).finished() && current(instance).wins() == 1 && current(instance).losses() == 0), "Victory did not automatically restart");
        server.runOnServer(instance -> current(instance).finish("Поражение"));
        context.waitTicks(105);
        require(server.computeOnServer(instance -> !current(instance).finished() && current(instance).wins() == 1 && current(instance).losses() == 1), "Loss or replay series counters were lost");
        leaveAndCheck();
        context.waitTicks(105);
        require(server.computeOnServer(instance -> current(instance) == null), "Autoreplay continued after leaving");
        pass("Nightmare difficulty, kit autoreplay toggle, victory/loss restarts, series counters and explicit stop work");
    }

    private void clearPotions(BotPlayer bot) {
        for (int slot = 0; slot < bot.getInventory().getContainerSize(); slot++) {
            if (bot.getInventory().getItem(slot).is(Items.POTION) || bot.getInventory().getItem(slot).is(Items.SPLASH_POTION)) {
                bot.getInventory().setItem(slot, ItemStack.EMPTY);
            }
        }
    }

    private void checkRecoveryAndBusyPearls() throws java.io.IOException {
        start("op", 1);
        context.waitTicks(65);
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            BotPlayer bot = match.bots.getFirst();
            bot.stopUsingItem();
            clearPotions(bot);
            bot.snapTo(0.5, 65, 0.5, 0, 0);
            match.owner.connection.teleport(0.5, 65, 3.5, 180, 0);
            bot.setHealth(8);
        });
        context.waitTicks(110);
        require(server.computeOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            return bot.brain.defensivePearls > 0 && bot.distanceTo(owner(instance)) > 10 && bot.hasEffect(MobEffects.REGENERATION);
        }), "Bot did not pearl away before eating to recover");
        pass("A threatened low-health bot throws a finite defensive pearl, gains distance and consumes a golden apple");
        leaveAndCheck();
        start("op", 1);
        context.waitTicks(65);
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            BotPlayer bot = match.bots.getFirst();
            bot.stopUsingItem();
            clearPotions(bot);
            bot.snapTo(0.5, 65, 20.5, 180, 0);
            match.owner.connection.teleport(0.5, 65, -20.5, 90, -10);
            bot.setHealth(6);
        });
        context.waitTicks(6);
        require(server.computeOnServer(instance -> current(instance).bots.getFirst().isUsingItem()), "Healing fixture did not begin eating");
        server.runOnServer(instance -> {
            ServerPlayer player = owner(instance);
            player.getInventory().setSelectedSlot(2);
            player.gameMode.useItem(player, player.level(), player.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND);
        });
        context.waitTicks(40);
        require(server.computeOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            return bot.brain.pearlsThrown == 0 && bot.hasEffect(MobEffects.REGENERATION);
        }), "Player pearl interrupted bot healing");
        pass("A player's pearl does not interrupt a bot eating a golden apple or waste a follow pearl");
        leaveAndCheck();
    }

    private void checkFallClutch() throws java.io.IOException {
        start("op", 1);
        context.waitTicks(65);
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            BotPlayer bot = match.bots.getFirst();
            bot.stopUsingItem();
            clearPotions(bot);
            for (int slot = 0; slot < 36; slot++) {
                if (bot.getInventory().getItem(slot).is(Items.GOLDEN_APPLE)) {
                    bot.getInventory().setItem(slot, ItemStack.EMPTY);
                }
            }
            bot.snapTo(0.5, 105, 0.5, 0, 0);
            bot.setOnGround(false);
            bot.setIgnoreFallDamageFromCurrentImpulse(false);
            bot.fallDistance = 7;
            bot.setDeltaMovement(new Vec3(0, -0.7, 0));
            match.owner.connection.teleport(30.5, 65, 30.5, 0, 0);
        });
        context.waitTicks(65);
        require(server.computeOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            return bot.brain.clutchPearls > 0 && !bot.defeated && bot.getHealth() > 10 && bot.onGround();
        }), "High difficulty bot failed its downward pearl fall clutch");
        pass("High difficulty bot throws a real pearl downward during a dangerous fall and survives without hidden healing");
        leaveAndCheck();
    }

    private void checkPressurePearls() throws java.io.IOException {
        start("op", 1);
        context.waitTicks(65);
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            BotPlayer bot = match.bots.getFirst();
            bot.stopUsingItem();
            clearPotions(bot);
            bot.snapTo(0.5, 65, -15.5, 0, 0);
            bot.getRandom().setSeed(173);
            match.owner.connection.teleport(0.5, 65, 4.5, 0, 0);
            match.owner.setHealth(8);
            match.owner.setAbsorptionAmount(0);
            match.owner.getFoodData().setFoodLevel(10);
            match.owner.getFoodData().setSaturation(0);
        });
        context.waitTicks(2);
        try {
            context.runOnClient(client -> {
                client.options.keyUp.setDown(true);
                client.options.keySprint.setDown(true);
            });
            context.waitTicks(90);
        } finally {
            context.runOnClient(client -> {
                client.options.keyUp.setDown(false);
                client.options.keySprint.setDown(false);
            });
        }
        require(server.computeOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            return bot.brain.pressurePearls == 1 && bot.brain.pearlsThrown == 1;
        }), server.computeOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            return "Bot did not pressure a fleeing low-health target with one rate-limited pearl: pressure=" + bot.brain.pressurePearls
                + ", pearls=" + bot.brain.pearlsThrown + ", health=" + owner(instance).getHealth() + ", distance=" + bot.distanceTo(owner(instance));
        }));
        pass("Bot uses a rate-limited pressure pearl against a fleeing low-health player without repeated throws");
        leaveAndCheck();
    }

    private void checkSpearAndFlight() throws java.io.IOException {
        start("mace", 1);
        context.waitTicks(65);
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            BotPlayer bot = match.bots.getFirst();
            match.kit.equip(bot);
            var registry = bot.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            require(bot.getInventory().getItem(5).is(Items.NETHERITE_SPEAR)
                && EnchantmentHelper.getItemEnchantmentLevel(registry.getOrThrow(Enchantments.LUNGE), bot.getInventory().getItem(5)) == 3, "Mace kit lacks Lunge III spear");
            bot.snapTo(-15.5, 65, 0.5, -90, 0);
            bot.setHealth(20);
            bot.brain.lungeUses = 0;
            match.owner.connection.teleport(15.5, 65, 0.5, 90, 0);
            match.owner.setAbsorptionAmount(200);
        });
        context.waitTicks(35);
        require(server.computeOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            return bot.brain.lungeUses > 0 && bot.getX() > -10;
        }), "Bot did not close distance using native spear Lunge III");
        pass("Mace kit includes Lunge III spear and bot uses its native dash to close distance");
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            BotPlayer bot = match.bots.getFirst();
            bot.stopUsingItem();
            match.kit.equip(bot);
            bot.snapTo(-22.5, 100, 0.5, -90, 0);
            bot.setOnGround(false);
            bot.setIgnoreFallDamageFromCurrentImpulse(false);
            bot.fallDistance = 7;
            bot.setDeltaMovement(new Vec3(0, -0.7, 0));
            match.owner.connection.teleport(22.5, 65, 0.5, 90, 0);
        });
        double closest = 45;
        for (int tick = 0; tick < 160; tick++) {
            context.waitTick();
            closest = Math.min(closest, server.computeOnServer(instance -> current(instance).bots.getFirst().position().subtract(owner(instance).position()).horizontalDistance()));
        }
        require(server.computeOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            return bot.brain.flightsStarted > 0 && bot.brain.rocketsUsed > 0 && bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.NETHERITE_CHESTPLATE);
        }) && closest < 20, "Bot did not fly with elytra/rockets and restore chestplate: closest=" + closest);
        require(server.computeOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            int wings = 0;
            for (int slot = 0; slot < bot.getInventory().getContainerSize(); slot++) {
                if (bot.getInventory().getItem(slot).is(Items.ELYTRA)) {
                    wings += bot.getInventory().getItem(slot).getCount();
                }
            }
            return wings == 1;
        }), "Elytra chestplate swap lost or duplicated equipment");
        pass("Bot equips real elytra when short of the target, consumes rockets, closes distance and restores its original chestplate without duplication");
        leaveAndCheck();
    }

    private void checkArenaBoundary() throws java.io.IOException {
        start("op", 1);
        context.waitTicks(95);
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            match.owner.connection.teleport(0.5, 113, -22.5, 0, 65);
            match.bots.getFirst().snapTo(20.5, 65, 20.5, 180, 0);
        });
        context.waitTicks(2);
        context.takeScreenshot("09-arena-overview");
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            match.owner.connection.teleport(48, 65, -49, 0, 0);
            match.bots.getFirst().snapTo(-48, 134, 49, 0, 0);
        });
        context.waitTicks(3);
        require(server.computeOnServer(instance -> {
            MatchSession match = current(instance);
            return Math.abs(match.owner.getX()) <= 39.4 && Math.abs(match.owner.getZ()) <= 39.4
                && Math.abs(match.bots.getFirst().getX()) <= 39.4 && match.bots.getFirst().getY() <= 126;
        }), "Arena boundary allowed an escaped participant");
        server.runOnServer(instance -> owner(instance).teleportTo(instance.overworld(), 0.5, 100, 0.5, Set.of(), 0, 0, false));
        context.waitTicks(3);
        require(server.computeOnServer(instance -> Arena.inside(owner(instance)) && current(instance) != null), "Dimension escape was not contained");
        pass("Decorated arena confines players and bots at horizontal/vertical boundaries and returns dimension escapes until explicit leave");
        leaveAndCheck();
    }

    private void checkMovementAndSword() throws java.io.IOException {
        start("axe", 1);
        context.waitTicks(65);
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            BotPlayer bot = match.bots.getFirst();
            bot.stopUsingItem();
            bot.snapTo(31.5, 65, 23.5, 0, 0);
            match.owner.connection.teleport(31.5, 65, 35.5, 180, 0);
            match.owner.setAbsorptionAmount(200);
            require(match.kit.items().get(5).is(Items.DIAMOND_SWORD), "Axe kit has no sword");
        });
        context.waitTicks(130);
        require(server.computeOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            return bot.distanceTo(owner(instance)) < 8 && bot.getMainHandItem().is(Items.DIAMOND_SWORD);
        }), "Advanced bot did not route around a ruin pillar with its sword");
        boolean axe = false;
        try {
            context.runOnClient(client -> client.options.keyUse.setDown(true));
            for (int tick = 0; tick < 80; tick++) {
                context.waitTick();
                if (server.computeOnServer(instance -> current(instance).bots.getFirst().getMainHandItem().is(Items.DIAMOND_AXE))) {
                    axe = true;
                    break;
                }
            }
        } finally {
            context.runOnClient(client -> client.options.keyUse.setDown(false));
        }
        require(axe, server.computeOnServer(instance -> "Sword bot did not switch to axe against a raised shield: blocking=" + owner(instance).isBlocking()
            + ", using=" + owner(instance).isUsingItem() + ", distance=" + current(instance).bots.getFirst().distanceTo(owner(instance))));
        pass("Advanced movement routes around ruin pillars, uses the axe kit sword and switches to axe against a shield");
        leaveAndCheck();
    }

    private void captureShowcase() throws java.io.IOException {
        configureShowcase();
        start("op", 1);
        context.waitTicks(100);
        context.runOnClient(client -> {
            client.options.hideGui = true;
            client.getToastManager().clear();
        });
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            match.owner.connection.teleport(0.5, 116, -24.5, 0, 67);
            match.bots.getFirst().snapTo(20.5, 65, 20.5, 180, 0);
        });
        context.waitTicks(3);
        showcaseShot("arena-overview");
        server.runOnServer(instance -> owner(instance).connection.teleport(-18.5, 69, -21.5, -35, 8));
        context.waitTicks(3);
        showcaseShot("arena-ground");
        server.runOnServer(instance -> TrainingService.get(instance).leave(owner(instance)));
        context.waitTicks(3);
        captureMenus();
        server.runOnServer(instance -> owner(instance).closeContainer());
        context.waitTicks(3);
        captureCombat();
    }

    private void configureShowcase() {
        context.getInput().resizeWindow(1280, 720);
        context.runOnClient(client -> {
            client.options.guiScale().set(3);
            client.options.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.HIDDEN);
            client.resizeDisplay();
        });
    }

    private void captureMenus() {
        server.runOnServer(instance -> TrainingService.get(instance).open(owner(instance), ""));
        context.waitForScreen(ContainerScreen.class);
        context.runOnClient(client -> client.options.hideGui = false);
        context.getInput().setCursorPos(1200, 680);
        showcaseShot("menu-settings");
        clickSlot(40);
        showcaseShot("menu-tuning");
        clickSlot(49);
        clickSlot(16);
        clickSlot(49);
        clickSlot(12);
        showcaseShot("menu-kits");
    }

    private void captureCombat() throws java.io.IOException {
        start("axe", 1);
        context.waitTicks(65);
        server.runOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            bot.stopUsingItem();
            bot.snapTo(0.5, 65, 0.5, 180, 0);
            bot.setDeltaMovement(Vec3.ZERO);
            owner(instance).connection.teleport(0.5, 65, -4.5, 0, 0);
        });
        int botId = server.computeOnServer(instance -> current(instance).bots.getFirst().getId());
        List<String> frameTicks = new ArrayList<>();
        for (int frame = 0; frame < 48; frame++) {
            context.runOnClient(client -> {
                client.getToastManager().clear();
                var target = client.level.getEntity(botId);
                if (target != null) {
                    client.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
                }
            });
            context.waitTick();
            frameTicks.add(Long.toString(context.computeOnClient(client -> client.level.getGameTime())));
            showcaseShot("combat-" + String.format(java.util.Locale.ROOT, "%03d", frame));
        }
        Files.write(Path.of("showcase", "combat-ticks.txt"), frameTicks);
        leaveAndCheck();
    }

    private void showcaseShot(String name) {
        context.runOnClient(client -> client.getToastManager().clear());
        context.takeScreenshot(net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions.of(name)
            .disableCounterPrefix().withDestinationDir(Path.of("showcase")));
    }

    private void checkCombatTempo() throws java.io.IOException {
        start("axe", 1);
        context.waitTicks(65);
        server.runOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            bot.stopUsingItem();
            bot.removeAllEffects();
            bot.getInventory().setItem(40, ItemStack.EMPTY);
            bot.getInventory().setSelectedSlot(5);
            bot.snapTo(0.5, 65, 0.5, 0, 0);
            bot.setDeltaMovement(Vec3.ZERO);
            bot.resetAttackStrengthTicker();
            owner(instance).setAbsorptionAmount(200);
        });
        for (int tick = 0; tick < 20; tick++) {
            server.runOnServer(instance -> {
                BotPlayer bot = current(instance).bots.getFirst();
                owner(instance).connection.teleport(bot.getX(), 65, bot.getZ() + 3.8, 180, 0);
            });
            context.waitTick();
        }
        double progress = server.computeOnServer(instance -> current(instance).bots.getFirst().getZ() - 0.5);
        require(progress > 4.8, "Close pursuit fell below native sprint pace: " + progress + " blocks in 20 ticks");
        pass("Close pursuit maintains native sprint pace instead of walking while preparing a critical: " + progress + " blocks in 20 ticks");
        server.runOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            bot.snapTo(0.5, 65, -15.5, 0, 0);
            bot.setDeltaMovement(Vec3.ZERO);
            owner(instance).connection.teleport(0.5, 65, 15.5, 180, 0);
        });
        int airborne = 0;
        for (int tick = 0; tick < 40; tick++) {
            context.waitTick();
            if (server.computeOnServer(instance -> !current(instance).bots.getFirst().onGround())) {
                airborne++;
            }
        }
        double chaseProgress = server.computeOnServer(instance -> current(instance).bots.getFirst().getZ() + 15.5);
        require(chaseProgress > 11.5 && airborne > 10, "Long pursuit did not use native sprint jumps: " + chaseProgress + " blocks, airborne=" + airborne);
        pass("Advanced pursuit uses native sprint jumps to cover distance quickly: " + chaseProgress + " blocks in 40 ticks");
        server.runOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            bot.stopUsingItem();
            bot.snapTo(0.5, 65, 0.5, 0, 0);
            bot.setDeltaMovement(Vec3.ZERO);
            bot.resetAttackStrengthTicker();
            bot.brain.criticalHits = 0;
            owner(instance).connection.teleport(0.5, 65, 2.8, 180, 0);
            owner(instance).getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        });
        int firstHit = 0;
        int previousHit = 0;
        int observedHits = 0;
        for (int tick = 1; tick <= 80; tick++) {
            context.waitTick();
            int currentHits = server.computeOnServer(instance -> current(instance).bots.getFirst().brain.criticalHits);
            if (currentHits > observedHits) {
                if (firstHit == 0) {
                    firstHit = tick;
                }
                require(previousHit == 0 || tick - previousHit >= 12, "Critical attacks bypassed native sword recharge");
                previousHit = tick;
                observedHits = currentHits;
            }
        }
        int hits = server.computeOnServer(instance -> current(instance).bots.getFirst().brain.criticalHits);
        server.runOnServer(instance -> owner(instance).getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE).setBaseValue(0));
        require(firstHit > 0 && firstHit <= 17 && hits >= 5, "Critical timing wasted a charged attack window: first=" + firstHit + ", hits=" + hits + " in 80 ticks");
        pass("Critical jumps prepare during native weapon recharge and land promptly without bypassing cooldowns: first=" + firstHit + ", hits=" + hits + " in 80 ticks");
        leaveAndCheck();
        start("mace", 1);
        context.waitTicks(65);
        server.runOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            bot.stopUsingItem();
            bot.removeAllEffects();
            bot.getInventory().setSelectedSlot(5);
            bot.snapTo(0.5, 65, 0.5, 0, 0);
            bot.setDeltaMovement(Vec3.ZERO);
            owner(instance).setAbsorptionAmount(200);
            owner(instance).connection.teleport(0.5, 65, 7, 180, 0);
            bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND);
            require(bot.isUsingItem(), "Spear charge did not begin");
        });
        for (int tick = 0; tick < 20; tick++) {
            server.runOnServer(instance -> {
                BotPlayer bot = current(instance).bots.getFirst();
                owner(instance).connection.teleport(bot.getX(), 65, bot.getZ() + 6.5, 180, 0);
            });
            context.waitTick();
        }
        double spearProgress = server.computeOnServer(instance -> current(instance).bots.getFirst().getZ() - 0.5);
        require(spearProgress > 4.8 && server.computeOnServer(instance -> {
            BotPlayer bot = current(instance).bots.getFirst();
            return bot.isUsingItem() && bot.isSprinting();
        }), "Charging a spear applied eating slowdown or disabled sprint: " + spearProgress + " blocks in 20 ticks");
        pass("Spear charging honors native item movement and sprint instead of applying the 20-percent eating slowdown: " + spearProgress + " blocks in 20 ticks");
        leaveAndCheck();
    }

    private void checkShieldCriticals() throws java.io.IOException {
        start("axe", 1);
        context.waitTicks(95);
        server.runOnServer(instance -> {
            MatchSession match = current(instance);
            BotPlayer bot = match.bots.getFirst();
            bot.stopUsingItem();
            bot.snapTo(0.5, 65, 0.5, 0, 0);
            bot.brain.criticalHits = 0;
            match.owner.connection.teleport(0.5, 65, 2.8, 180, 0);
            match.owner.setAbsorptionAmount(200);
        });
        context.waitTicks(35);
        require(server.computeOnServer(instance -> current(instance).bots.getFirst().brain.criticalHits > 0), "Shield or weapon switching interrupted a ready jumping critical attack");
        pass("A bot carrying a shield commits to its jumping critical and strikes in the first available fall window");
        leaveAndCheck();
    }

    private void pass(String name) {
        passed.add(name);
        dev.koefi.boxpvp.BoxPvp.LOGGER.info("TEST PASSED: {}", name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
