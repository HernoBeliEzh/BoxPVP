package dev.koefi.boxpvp.bot;

import dev.koefi.boxpvp.combat.CombatRule;
import dev.koefi.boxpvp.combat.DifficultyProfile;
import dev.koefi.boxpvp.combat.CombatTuning;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

public final class BotBrain {
    private final BotPlayer bot;
    private final int index;
    private final DifficultyProfile difficulty;
    private final CombatTuning tuning;
    private final BotRecovery recovery;
    private final BotFlight flight;
    private Vec3 perceived = Vec3.ZERO;
    private float yawError;
    private float pitchError;
    private int decision;
    private int actionCooldown;
    private int boostCooldown;
    private int shieldUntil;
    private int strafe = 1;
    private boolean repairing;
    private boolean wantCritical;
    private long observedPearl;
    private Vec3 pearlGoal;
    private int pearlReaction;
    private int pearlExpiry;
    private float pearlYaw;
    private float pearlPitch;
    private int lungeCooldown;
    private int nextChasePearl;
    private int nextPressureCheck;
    private boolean pressureThrow;
    private int nextStrafe;
    private int lastAttack = -20;
    private boolean criticalJump;
    public int smashHits;
    public int criticalHits;
    public int pearlsThrown;
    public int defensivePearls;
    public int clutchPearls;
    public int lungeUses;
    public int flightsStarted;
    public int rocketsUsed;
    public int pressurePearls;

    public BotBrain(BotPlayer bot, int index) {
        this.bot = bot;
        this.index = index;
        difficulty = bot.session.settings.difficulty();
        tuning = bot.session.settings.tuning();
        recovery = new BotRecovery(bot, this, tuning);
        flight = new BotFlight(bot, this);
        decision = index % Math.max(1, tuning.reaction());
    }

    public void tick() {
        bot.xxa = 0;
        bot.zza = 0;
        bot.setJumping(false);
        if (!bot.session.active() || bot.defeated || !bot.session.owner.isAlive()) {
            bot.stopUsingItem();
            bot.setSprinting(false);
            return;
        }
        if (bot.onGround()) {
            criticalJump = false;
        }
        if (actionCooldown > 0) {
            actionCooldown--;
        }
        if (boostCooldown > 0) {
            boostCooldown--;
        }
        if (lungeCooldown > 0) {
            lungeCooldown--;
        }
        var target = bot.session.owner;
        if (decision-- <= 0) {
            double prediction = difficulty == DifficultyProfile.NIGHTMARE ? 3 : difficulty.ordinal() >= 3 ? 1.25 : difficulty.ordinal() == 2 ? 0.5 : 0;
            perceived = target.position().add(target.getKnownMovement().scale(prediction)).add(0, target.getBbHeight() * 0.6, 0);
            yawError = (bot.getRandom().nextFloat() * 2 - 1) * tuning.aimError();
            pitchError = (bot.getRandom().nextFloat() * 2 - 1) * tuning.aimError();
            decision = tuning.reaction() - 1;
            if (bot.tickCount >= nextStrafe) {
                Vec3 sidewaysMotion = target.getKnownMovement().multiply(1, 0, 1);
                double angle = Math.toRadians(bot.getYRot());
                double lateral = sidewaysMotion.x * Math.cos(angle) + sidewaysMotion.z * Math.sin(angle);
                strafe = difficulty.ordinal() >= 3 && Math.abs(lateral) > 0.08 ? lateral > 0 ? -1 : 1 : -strafe;
                nextStrafe = bot.tickCount + (difficulty.ordinal() >= 3 ? 10 : 20) + bot.getRandom().nextInt(15);
            }
            if (!criticalJump) {
                wantCritical = bot.session.settings.rule() == CombatRule.CRITICAL
                    || bot.session.settings.rule() == CombatRule.NORMAL && bot.getRandom().nextInt(100) < tuning.criticalChance();
            }
        }
        double distance = bot.distanceTo(target);
        if (flight.tick() || fallClutch()) {
            return;
        }
        boolean committedAir = !bot.onGround() && (bot.getMainHandItem().is(Items.MACE) || criticalJump) && bot.getHealth() > 4;
        if (!committedAir && recovery.tick(distance)) {
            return;
        }
        if ((!committedAir || bot.position().subtract(target.position()).horizontalDistance() > 3.5) && (followPearl() || pressurePearl())) {
            return;
        }
        if (bot.isUsingItem()) {
            aim(perceived, yawError, pitchError);
            if (bot.getUseItem().is(Items.SHIELD)) {
                if (bot.tickCount >= shieldUntil || distance > 4.5) {
                    bot.stopUsingItem();
                } else {
                    move(-0.25f, strafe * 0.2f, false);
                    return;
                }
            } else if (bot.getUseItem().has(DataComponents.KINETIC_WEAPON)) {
                if (distance < 2.5 || bot.getTicksUsingItem() > 35) {
                    bot.stopUsingItem();
                } else {
                    move(1, strafe * 0.08f, true);
                    return;
                }
            } else {
                move(-0.7f, strafe * 0.25f, false);
                return;
            }
        }
        double durability = armorFraction();
        repairing = (durability < 0.35 || repairing && durability < 0.85) && find(stack -> stack.is(Items.EXPERIENCE_BOTTLE)) >= 0;
        if (!committedAir && actionCooldown == 0 && useSupplies(distance)) {
            return;
        }
        boolean retreat = repairing || bot.getHealth() < 7 && find(stack -> stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) >= 0;
        if (!committedAir && retreat && distance < 7) {
            move(-1, strafe * 0.3f, true);
            return;
        }
        ItemStack previousWeapon = bot.getMainHandItem();
        selectWeapon(target.isBlocking());
        ItemStack weapon = bot.getMainHandItem();
        if (!ItemStack.isSameItem(previousWeapon, weapon)) {
            return;
        }
        boolean mace = weapon.is(Items.MACE);
        boolean spear = weapon.is(ItemTags.SPEARS);
        if (!(mace && bot.onGround() && distance < 6 && boostCooldown == 0 && find(stack -> stack.is(Items.WIND_CHARGE)) >= 0)) {
            aim(perceived, yawError, pitchError);
        }
        double horizontal = bot.position().subtract(target.position()).horizontalDistance();
        double desired = spear ? 3.5 : mace && !bot.onGround() ? 0.45 : 2.25;
        if (difficulty.ordinal() >= 3 && !mace && !spear && bot.onGround()) {
            desired = bot.getAttackStrengthScale(0.5f) < 0.7f && target.getAttackStrengthScale(0.5f) > 0.75f ? 2.8 : 2.1;
        }
        float forward = horizontal > desired + 0.2 ? 1 : horizontal < desired - 0.35 ? -0.25f : 0.12f;
        float sideways = mace && !bot.onGround() ? 0 : distance < 8 ? strafe * (0.08f + tuning.strafe() * 0.005f) : (index % 2 == 0 ? 0.12f : -0.12f);
        if (difficulty.ordinal() >= 3) {
            sideways *= 0.8f + 0.2f * Mth.sin((bot.tickCount + index * 11) * 0.24f);
            if (bot.onGround() && bot.tickCount - lastAttack < 2) {
                forward *= 0.3f;
            }
        }
        if (tuning.strafe() == 0) {
            sideways = 0;
        }
        boolean sprint = forward > 0 && (!wantCritical || distance > 4) && (!mace || bot.onGround());
        move(forward, sideways, sprint);
        if (mace && bot.onGround() && distance < 6 && boostCooldown == 0) {
            int charge = find(stack -> stack.is(Items.WIND_CHARGE));
            if (charge >= 0) {
                if (bot.getAttackStrengthScale(0.5f) < tuning.charge() / 100f) {
                    aim(perceived, yawError, pitchError);
                    return;
                }
                aimAngles(bot.getYRot(), 90);
                move(0, 0, false);
                if (bot.getXRot() > 75 && useWindCharge(charge)) {
                    boostCooldown = difficulty.ordinal() >= 3 ? 32 : 48;
                    actionCooldown = 4;
                }
                return;
            }
        }
        float requiredCharge = (wantCritical && !mace && !spear ? Math.max(91, tuning.charge()) : tuning.charge()) / 100f;
        if (bot.getAttackStrengthScale(0.5f) < requiredCharge || bot.cannotAttackWithItem(weapon, 5)) {
            return;
        }
        if (spear && distance > 6 && distance < 26 && bot.onGround() && lungeCooldown == 0 && bot.getFoodData().getFoodLevel() >= 8
            && Math.abs(Mth.wrapDegrees(desiredYaw(perceived) - bot.getYRot())) < 4 && weapon.has(DataComponents.PIERCING_WEAPON)) {
            weapon.get(DataComponents.PIERCING_WEAPON).attack(bot, EquipmentSlot.MAINHAND);
            lungeUses++;
            lungeCooldown = difficulty.ordinal() >= 3 ? 20 : 35;
            return;
        }
        if (spear && distance > 3 && distance < 7 && bot.tickCount % 70 < 25 && bot.session.settings.rule() == CombatRule.NORMAL) {
            use(bot.getInventory().getSelectedSlot());
            return;
        }
        if (!mace && !spear && wantCritical && bot.onGround() && horizontal < 3.1 && bot.hasLineOfSight(target)) {
            bot.setSprinting(false);
            criticalJump = true;
            bot.jumpFromGround();
            return;
        }
        boolean falling = bot.fallDistance > 0 && !bot.onGround() && bot.getDeltaMovement().y < 0;
        if (mace && bot.onGround() && find(stack -> stack.is(Items.WIND_CHARGE)) < 0) {
            bot.jumpFromGround();
            return;
        }
        if (wantCritical && !mace && !spear && !falling || mace && (!falling || !net.minecraft.world.item.MaceItem.canSmashAttack(bot) && find(stack -> stack.is(Items.WIND_CHARGE)) >= 0)) {
            return;
        }
        if (bot.session.settings.rule() == CombatRule.COMBO && !bot.onGround() && !bot.isSprinting()) {
            return;
        }
        var hit = bot.entityAttackRange().getClosesetHit(bot, 1, entity -> entity != bot && entity.isPickable() && !entity.isSpectator());
        if (!(hit instanceof EntityHitResult entityHit) || entityHit.getEntity() != target || !bot.hasLineOfSight(target)) {
            return;
        }
        bot.stopUsingItem();
        lastAttack = bot.tickCount;
        criticalJump = false;
        if (spear && weapon.has(DataComponents.PIERCING_WEAPON)) {
            weapon.get(DataComponents.PIERCING_WEAPON).attack(bot, EquipmentSlot.MAINHAND);
        } else {
            boolean smash = mace && net.minecraft.world.item.MaceItem.canSmashAttack(bot);
            boolean critical = falling && !bot.isSprinting();
            float healthBefore = target.getHealth() + target.getAbsorptionAmount();
            bot.attack(target);
            bot.swing(InteractionHand.MAIN_HAND, true);
            if (target.getHealth() + target.getAbsorptionAmount() < healthBefore || bot.session.finished()) {
                if (smash) {
                    smashHits++;
                }
                if (critical) {
                    criticalHits++;
                }
            }
        }
        if (bot.session.settings.rule() == CombatRule.COMBO) {
            bot.setSprinting(false);
        }
    }

    private boolean useSupplies(double distance) {
        if (distance > 4 && !bot.hasEffect(MobEffects.STRENGTH)) {
            int potion = find(stack -> (stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION)) && stack.getOrDefault(DataComponents.POTION_CONTENTS, net.minecraft.world.item.alchemy.PotionContents.EMPTY).is(Potions.STRONG_STRENGTH));
            if (potion >= 0 && usePotion(potion)) {
                return true;
            }
        }
        if (distance > 4 && !bot.hasEffect(MobEffects.SPEED)) {
            int potion = find(stack -> (stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION)) && stack.getOrDefault(DataComponents.POTION_CONTENTS, net.minecraft.world.item.alchemy.PotionContents.EMPTY).is(Potions.STRONG_SWIFTNESS));
            if (potion >= 0 && usePotion(potion)) {
                return true;
            }
        }
        if (repairing && distance > 5) {
            int experience = find(stack -> stack.is(Items.EXPERIENCE_BOTTLE));
            if (experience >= 0) {
                aim(bot.position().add(0, -2, 0), 0, 0);
                if (bot.getXRot() > 75 && use(experience)) {
                    actionCooldown = 5;
                }
                move(-0.2f, 0, false);
                return true;
            }
            repairing = false;
        }
        if (bot.getFoodData().getFoodLevel() < 15 && distance > 4) {
            int food = find(stack -> stack.has(DataComponents.FOOD) && !stack.is(Items.GOLDEN_APPLE) && !stack.is(Items.ENCHANTED_GOLDEN_APPLE));
            if (food >= 0 && use(food)) {
                return true;
            }
        }
        if (bot.onGround() && distance < 4 && bot.getOffhandItem().is(Items.SHIELD) && !bot.getCooldowns().isOnCooldown(bot.getOffhandItem())
            && !(distance < 3.5 && bot.getAttackStrengthScale(0.5f) > 0.95f)
            && bot.session.owner.getAttackStrengthScale(0.5f) > 0.7f && bot.getRandom().nextInt(100) < tuning.shieldChance()) {
            aim(perceived, yawError, pitchError);
            bot.gameMode.useItem(bot, bot.level(), bot.getOffhandItem(), InteractionHand.OFF_HAND);
            shieldUntil = bot.tickCount + 6 + bot.getRandom().nextInt(8);
            actionCooldown = 15;
            return true;
        }
        return false;
    }

    private boolean usePotion(int slot) {
        if (bot.getInventory().getItem(slot).is(Items.SPLASH_POTION)) {
            aimAngles(bot.getYRot(), 90);
            move(0, 0, false);
            if (bot.getXRot() < 85) {
                return true;
            }
            if (useOffhand(slot)) {
                actionCooldown = 12;
                return true;
            }
            return false;
        }
        return use(slot);
    }

    private double armorFraction() {
        double minimum = 1;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = bot.getItemBySlot(slot);
            var mending = bot.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(net.minecraft.world.item.enchantment.Enchantments.MENDING);
            if (stack.isDamageableItem() && net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(mending, stack) > 0) {
                minimum = Math.min(minimum, 1.0 - (double) stack.getDamageValue() / stack.getMaxDamage());
            }
        }
        return minimum;
    }

    private void selectWeapon(boolean blocking) {
        if (criticalJump && !bot.onGround() && !bot.getMainHandItem().isEmpty()) {
            return;
        }
        int selected = blocking ? find(stack -> stack.is(ItemTags.AXES)) : -1;
        if (selected < 0 && bot.onGround() && find(stack -> stack.is(Items.MACE)) >= 0
            && (bot.distanceTo(bot.session.owner) > 7 || bot.getMainHandItem().is(ItemTags.SPEARS) && bot.distanceTo(bot.session.owner) > 5.5)) {
            selected = find(stack -> stack.is(ItemTags.SPEARS));
        }
        if (selected < 0) {
            selected = find(stack -> stack.is(Items.MACE));
        }
        if (selected < 0) {
            selected = find(stack -> stack.is(ItemTags.SWORDS));
        }
        if (selected < 0) {
            selected = find(stack -> stack.is(ItemTags.SPEARS) || stack.is(ItemTags.AXES));
        }
        if (selected >= 0) {
            select(selected);
        }
    }

    int find(Predicate<ItemStack> predicate) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (!stack.isEmpty() && predicate.test(stack)) {
                return slot;
            }
        }
        if (!bot.getOffhandItem().isEmpty() && predicate.test(bot.getOffhandItem())) {
            return 40;
        }
        return -1;
    }

    private void select(int slot) {
        if (slot >= 9) {
            int destination = 8;
            ItemStack previous = bot.getInventory().getItem(destination);
            bot.getInventory().setItem(destination, bot.getInventory().getItem(slot));
            bot.getInventory().setItem(slot, previous);
            slot = destination;
        }
        bot.getInventory().setSelectedSlot(slot);
    }

    boolean use(int slot) {
        if (slot == 40) {
            return bot.gameMode.useItem(bot, bot.level(), bot.getOffhandItem(), InteractionHand.OFF_HAND).consumesAction();
        }
        select(slot);
        ItemStack stack = bot.getMainHandItem();
        if (bot.getCooldowns().isOnCooldown(stack)) {
            return false;
        }
        var result = bot.gameMode.useItem(bot, bot.level(), stack, InteractionHand.MAIN_HAND);
        return result.consumesAction();
    }

    private boolean useWindCharge(int slot) {
        return useOffhand(slot);
    }

    boolean useOffhand(int slot) {
        if (slot == 40) {
            return use(slot);
        }
        ItemStack previous = bot.getOffhandItem();
        bot.getInventory().setItem(40, bot.getInventory().getItem(slot));
        bot.getInventory().setItem(slot, previous);
        boolean used = use(40);
        bot.getInventory().setItem(slot, bot.getOffhandItem());
        bot.getInventory().setItem(40, previous);
        return used;
    }

    float desiredYaw(Vec3 position) {
        Vec3 delta = position.subtract(bot.getEyePosition());
        return (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90;
    }

    void aim(Vec3 position, float yawError, float pitchError) {
        Vec3 delta = position.subtract(bot.getEyePosition());
        float yaw = desiredYaw(position) + yawError;
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance())) + pitchError;
        aimAngles(yaw, pitch);
    }

    void aimAngles(float yaw, float pitch) {
        bot.setYRot(Mth.approachDegrees(bot.getYRot(), yaw, tuning.turnSpeed()));
        bot.setXRot(Mth.clamp(Mth.approachDegrees(bot.getXRot(), pitch, tuning.turnSpeed()), -90, 90));
        bot.setYHeadRot(bot.getYRot());
    }

    private boolean followPearl() {
        if (!tuning.followPearls()) {
            return false;
        }
        if (observedPearl != bot.session.pearlSequence && bot.session.pearlLanding != null) {
            observedPearl = bot.session.pearlSequence;
            Vec3 landing = bot.session.pearlLanding;
            pearlGoal = null;
            pressureThrow = false;
            if (bot.isUsingItem() && !bot.getUseItem().is(Items.SHIELD) || bot.getHealth() < tuning.healHealth() && bot.hasEffect(MobEffects.REGENERATION)
                || bot.tickCount < nextChasePearl || bot.position().distanceTo(landing) < 8
                || difficulty.ordinal() < 3 && bot.getRandom().nextInt(100) >= 35 + difficulty.ordinal() * 20) {
                return false;
            }
            pearlGoal = new Vec3(Mth.clamp(landing.x, -37, 37), Math.max(65, landing.y), Mth.clamp(landing.z, -37, 37));
            pearlReaction = bot.tickCount + tuning.reaction();
            pearlExpiry = bot.tickCount + 80;
            pearlYaw = desiredYaw(pearlGoal);
            pearlPitch = PearlTrajectory.pitch(bot, pearlGoal, pearlYaw);
        }
        if (pearlGoal == null || bot.tickCount < pearlReaction || bot.getHealth() <= 6) {
            return false;
        }
        if (bot.isUsingItem() && !bot.getUseItem().is(Items.SHIELD)) {
            pearlGoal = null;
            return false;
        }
        int pearl = find(stack -> stack.is(Items.ENDER_PEARL));
        if (pearl < 0 || bot.tickCount > pearlExpiry) {
            pearlGoal = null;
            return false;
        }
        if (bot.getCooldowns().isOnCooldown(bot.getInventory().getItem(pearl))) {
            return false;
        }
        bot.stopUsingItem();
        aimAngles(pearlYaw, pearlPitch);
        move(0, 0, false);
        if (Math.abs(Mth.wrapDegrees(pearlYaw - bot.getYRot())) < Math.max(1, tuning.aimError()) && Math.abs(pearlPitch - bot.getXRot()) < 1.5 && use(pearl)) {
            pearlsThrown++;
            if (pressureThrow) {
                pressurePearls++;
            }
            nextChasePearl = bot.tickCount + (pressureThrow ? 100 : 60);
            pearlGoal = null;
            actionCooldown = 15;
        }
        return true;
    }

    private boolean pressurePearl() {
        if (!tuning.followPearls() || pearlGoal != null || bot.tickCount < nextChasePearl || bot.tickCount < nextPressureCheck
            || bot.isUsingItem() || bot.getHealth() < 10 || bot.getHealth() < tuning.healHealth() && bot.hasEffect(MobEffects.REGENERATION)) {
            return false;
        }
        nextPressureCheck = bot.tickCount + 30;
        var target = bot.session.owner;
        Vec3 movement = target.getKnownMovement().multiply(1, 0, 1);
        Vec3 separation = target.position().subtract(bot.position()).multiply(1, 0, 1);
        double distance = separation.length();
        boolean vulnerable = target.getHealth() + target.getAbsorptionAmount() <= 9;
        int pearl = find(stack -> stack.is(Items.ENDER_PEARL));
        if (distance < (vulnerable ? 9 : 16) || distance > 34 || movement.dot(separation.normalize()) < 0.08 || pearl < 0
            || bot.getInventory().getItem(pearl).getCount() <= 3 || bot.getCooldowns().isOnCooldown(bot.getInventory().getItem(pearl))) {
            return false;
        }
        int chance = vulnerable ? 20 + difficulty.ordinal() * 15 : 5 + difficulty.ordinal() * 5;
        if (bot.getRandom().nextInt(100) >= chance) {
            return false;
        }
        Vec3 predicted = target.position().add(movement.scale(10));
        pearlGoal = new Vec3(Mth.clamp(predicted.x, -36, 36), 65, Mth.clamp(predicted.z, -36, 36));
        pearlYaw = desiredYaw(pearlGoal);
        pearlPitch = PearlTrajectory.pitch(bot, pearlGoal, pearlYaw);
        pearlReaction = bot.tickCount + tuning.reaction();
        pearlExpiry = bot.tickCount + Math.max(80, tuning.reaction() + 40);
        pressureThrow = true;
        return followPearl();
    }

    void ignorePlayerPearl() {
        observedPearl = bot.session.pearlSequence;
        pearlGoal = null;
    }

    private boolean fallClutch() {
        if (difficulty.ordinal() < DifficultyProfile.HARD.ordinal() || bot.onGround() || bot.isFallFlying()
            || bot.isIgnoringFallDamageFromCurrentImpulse() || bot.fallDistance < 5 || bot.getY() < 70 || bot.getDeltaMovement().y > -0.5) {
            return false;
        }
        if (bot.getMainHandItem().is(Items.MACE) && bot.position().subtract(bot.session.owner.position()).horizontalDistance() < 8
            && bot.getAttackStrengthScale(0.5f) > 0.9) {
            return false;
        }
        int pearl = find(stack -> stack.is(Items.ENDER_PEARL));
        if (pearl < 0 || bot.getCooldowns().isOnCooldown(bot.getInventory().getItem(pearl))) {
            return false;
        }
        bot.stopUsingItem();
        aimAngles(bot.getYRot(), 90);
        move(0, 0, false);
        if (bot.getXRot() >= 85 && useOffhand(pearl)) {
            clutchPearls++;
            actionCooldown = 8;
        }
        return true;
    }

    void move(float forward, float sideways, boolean sprint) {
        if (Math.abs(bot.getX()) > 36 || Math.abs(bot.getZ()) > 36) {
            double angle = Math.toRadians(bot.getYRot());
            Vec3 center = new Vec3(-bot.getX(), 0, -bot.getZ()).normalize();
            forward = (float) (-Math.sin(angle) * center.x + Math.cos(angle) * center.z);
            sideways = (float) (Math.cos(angle) * center.x + Math.sin(angle) * center.z);
        }
        if (bot.onGround() && !bot.isUsingItem() && Math.abs(forward) + Math.abs(sideways) > 0.2f) {
            double angle = Math.toRadians(bot.getYRot());
            Vec3 direction = new Vec3(-Math.sin(angle) * forward + Math.cos(angle) * sideways, 0,
                Math.cos(angle) * forward + Math.sin(angle) * sideways).normalize().scale(1.2);
            if (!bot.level().noCollision(bot, bot.getBoundingBox().move(direction))) {
                if (bot.level().noCollision(bot, bot.getBoundingBox().move(direction.add(0, 1.1, 0)))) {
                    bot.jumpFromGround();
                } else if (difficulty.ordinal() >= 3) {
                    Vec3 left = new Vec3(Math.cos(angle) * 1.2, 0, Math.sin(angle) * 1.2);
                    boolean openLeft = bot.level().noCollision(bot, bot.getBoundingBox().move(left));
                    boolean openRight = bot.level().noCollision(bot, bot.getBoundingBox().move(left.scale(-1)));
                    if (openLeft || openRight) {
                        sideways = openLeft && !openRight ? 0.9f : openRight && !openLeft ? -0.9f : strafe * 0.9f;
                        forward *= 0.2f;
                    }
                }
            }
        }
        float length = Math.max(1, Mth.sqrt(forward * forward + sideways * sideways));
        bot.zza = forward / length * (bot.isUsingItem() ? 0.2f : 1);
        bot.xxa = sideways / length * (bot.isUsingItem() ? 0.2f : 1);
        bot.setSprinting(sprint && forward > 0 && !bot.isUsingItem() && bot.getFoodData().getFoodLevel() > 6);
    }
}
