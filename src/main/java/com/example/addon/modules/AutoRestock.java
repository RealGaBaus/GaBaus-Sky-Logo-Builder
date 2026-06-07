package com.example.addon.modules;

import com.example.addon.GaBausSkyLogoBuilder;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;

public class AutoRestock extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCoords = settings.createGroup("Coordinates");
    private final SettingGroup sgPearl = settings.createGroup("Pearl Precision");

    public enum Mode { BaseGuardian, Kitbot }

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("The restock mode to use.")
        .defaultValue(Mode.BaseGuardian)
        .build()
    );

    private final Setting<String> helperName = sgGeneral.add(new StringSetting.Builder().name("helper-name").defaultValue("HelperName").visible(() -> mode.get() == Mode.BaseGuardian).build());
    private final Setting<String> homeBaseName = sgGeneral.add(new StringSetting.Builder().name("home-base-name").defaultValue("last-location").visible(() -> mode.get() == Mode.BaseGuardian).build());

    private final Setting<String> kitbotName = sgGeneral.add(new StringSetting.Builder().name("kitbot-name").defaultValue("Kitbot").visible(() -> mode.get() == Mode.Kitbot).build());
    private final Setting<String> obsidianKitCommand = sgGeneral.add(new StringSetting.Builder().name("obsidian-command").description("Command to send when obsidian is needed.").defaultValue("$kit obby 1").visible(() -> mode.get() == Mode.Kitbot).build());
    private final Setting<String> cryingKitCommand = sgGeneral.add(new StringSetting.Builder().name("crying-command").description("Command to send when crying obsidian is needed.").defaultValue("$kit crying 1").visible(() -> mode.get() == Mode.Kitbot).build());
    private final Setting<String> netheriteKitCommand = sgGeneral.add(new StringSetting.Builder().name("netherite-command").description("Command to send when netherite blocks are needed.").defaultValue("$kit netherite 1").visible(() -> mode.get() == Mode.Kitbot).build());

    private final Setting<Boolean> autoAcceptTpa = sgGeneral.add(new BoolSetting.Builder().name("auto-accept-tpa").description("Automatically accept TPA requests from the kitbot.").defaultValue(true).visible(() -> mode.get() == Mode.Kitbot).build());
    private final Setting<String> tpaMessageTrigger = sgGeneral.add(new StringSetting.Builder().name("tpa-message-trigger").description("Text to look for in chat to detect a TPA request.").defaultValue("wants to teleport to you").visible(() -> mode.get() == Mode.Kitbot).build());
    private final Setting<String> tpAcceptCommand = sgGeneral.add(new StringSetting.Builder().name("tp-accept-command").description("Command used to accept a TPA request.").defaultValue("/tpy KitBot").visible(() -> mode.get() == Mode.Kitbot).build());
    private final Setting<Integer> restockDelay = sgGeneral.add(new IntSetting.Builder().name("restock-delay-min").description("Delay in minutes before sending the kit command.").defaultValue(1).min(0).sliderMax(60).visible(() -> mode.get() == Mode.Kitbot).build());
    private final Setting<Integer> kitSafeRadius = sgGeneral.add(new IntSetting.Builder().name("kit-safe-radius").description("Radius in chunks to search for a solid floor.").defaultValue(5).min(0).sliderMax(20).visible(() -> mode.get() == Mode.Kitbot).build());
    private final Setting<Boolean> repeatUntilFound = sgGeneral.add(new BoolSetting.Builder().name("repeat-until-found").description("Keep sending the command until a new shulker is detected.").defaultValue(true).visible(() -> mode.get() == Mode.Kitbot).build());

    private final Setting<Integer> obsidianToTake = sgGeneral.add(new IntSetting.Builder().name("obsidian-shulkers").description("How many obsidian shulkers to take.").defaultValue(3).min(0).sliderMax(10).visible(() -> mode.get() == Mode.BaseGuardian).build());
    private final Setting<Integer> cryingToTake = sgGeneral.add(new IntSetting.Builder().name("crying-shulkers").description("How many crying obsidian shulkers to take.").defaultValue(3).min(0).sliderMax(10).visible(() -> mode.get() == Mode.BaseGuardian).build());
    private final Setting<Boolean> useBaritone = sgGeneral.add(new BoolSetting.Builder().name("use-baritone").defaultValue(true).build());

    private final Setting<BlockPos> obsidianChestPos = sgCoords.add(new BlockPosSetting.Builder().name("obsidian-chest").defaultValue(BlockPos.ORIGIN).visible(() -> mode.get() == Mode.BaseGuardian).build());
    private final Setting<BlockPos> cryingChestPos = sgCoords.add(new BlockPosSetting.Builder().name("crying-obsidian-chest").defaultValue(BlockPos.ORIGIN).visible(() -> mode.get() == Mode.BaseGuardian).build());

    private final Setting<Integer> pearlDelay = sgPearl.add(new IntSetting.Builder().name("pearl-delay").description("Delay in ticks after detecting the pearl before throwing it.").defaultValue(40).min(0).sliderMax(100).visible(() -> mode.get() == Mode.BaseGuardian).build());

    private enum State {
        IDLE, HOMES_DEL, HOMES_SET, ALERT, WAIT_TP,
        LOOK_DOWN, THROW,
        GOTO_OBS, OPEN_OBS, LOOT_OBS, GOTO_CRY, OPEN_CRY, LOOT_CRY, RETURN,
        KIT_MOVE_TO_SAFE, KIT_WAIT, KIT_SEND, KIT_CHECK, KIT_PICKUP
    }

    private State state = State.IDLE;
    private int timer = 0;
    private int pearlCount = 0;
    private int initialShulkerCount = 0;
    private int tpaCooldown = 0;
    private int kitRetryTimer = 0;
    private boolean shulkerPickedUp = false;
    private boolean tpaAccepted = false;
    private ItemEntity targetItem;
    private BlockPos lastBaritoneGoal;
    private BlockPos shulkerTargetPos;
    private Method getSchematicWorldMethod;

    public AutoRestock() {
        super(GaBausSkyLogoBuilder.CATEGORY, "auto-restock", "stasis restocker.");
    }

    @Override
    public void onActivate() {
        tpaCooldown = 0;
        shulkerPickedUp = false;
        tpaAccepted = false;
        kitRetryTimer = 0;
        if (mode.get() == Mode.BaseGuardian) {
            state = State.HOMES_DEL;
            timer = 0;
        } else {
            initLitematica();
            state = State.KIT_MOVE_TO_SAFE;
            timer = 0;
            initialShulkerCount = countShulkers();
        }
    }

    private void initLitematica() {
        try {
            Class<?> swh = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
            for (Method m : swh.getDeclaredMethods()) {
                if (m.getReturnType().getName().contains("WorldSchematic") && m.getParameterCount() == 0) {
                    getSchematicWorldMethod = m;
                    getSchematicWorldMethod.setAccessible(true);
                    break;
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onDeactivate() { stop(); }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (mc.world == null || mc.player == null || mode.get() != Mode.Kitbot || !isActive() || !autoAcceptTpa.get()) return;
        if (tpaCooldown > 0) return;

        String msg = event.getMessage().getString();
        if (msg.toLowerCase().contains(kitbotName.get().toLowerCase()) && msg.toLowerCase().contains(tpaMessageTrigger.get().toLowerCase())) {
            info("Accepting TPA from " + kitbotName.get());
            String cmd = tpAcceptCommand.get();
            if (!cmd.startsWith("/")) cmd = "/" + cmd;
            sendMessage(cmd);
            tpaCooldown = 100;
            tpaAccepted = true;
        }
    }

    private void sendMessage(String msg) {
        if (msg == null || msg.isEmpty()) return;
        if (msg.startsWith("/")) mc.player.networkHandler.sendChatCommand(msg.substring(1));
        else mc.player.networkHandler.sendChatMessage(msg);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (mc.currentScreen instanceof GameMenuScreen) mc.setScreen(null);
        if (tpaCooldown > 0) tpaCooldown--;
        if (timer > 0) { timer--; return; }
        if (mode.get() == Mode.BaseGuardian) tickBaseGuardian();
        else tickKitbot();
    }

    private void tickBaseGuardian() {
        switch (state) {
            case HOMES_DEL -> { sendMessage("/delhome " + homeBaseName.get()); state = State.HOMES_SET; timer = 15; }
            case HOMES_SET -> { sendMessage("/sethome " + homeBaseName.get()); state = State.ALERT; timer = 15; }
            case ALERT -> {
                sendMessage("/msg " + helperName.get() + " #restock");
                state = State.WAIT_TP;
                info("Message sent. Waiting for TP and pearl...");
            }
            case WAIT_TP -> {
                if (mc.player.getBlockPos().isWithinDistance(obsidianChestPos.get(), 60)) {
                    if (InvUtils.find(Items.ENDER_PEARL).found()) {
                        info("TP detected and pearl found. Applying pearl delay...");
                        stop();
                        state = State.LOOK_DOWN;
                        timer = pearlDelay.get();
                    } else if (mc.player.age % 60 == 0) {
                        info("TP detected but pearl is missing. Waiting...");
                    }
                } else if (mc.player.age % 60 == 0) {
                    info("Waiting for TP... Distance to chest: " + (int) Math.sqrt(mc.player.squaredDistanceTo(Vec3d.ofCenter(obsidianChestPos.get()))) + " blocks.");
                }
            }
            case LOOK_DOWN -> {
                double centerX = Math.floor(mc.player.getX()) + 0.5;
                double centerZ = Math.floor(mc.player.getZ()) + 0.5;
                BlockPos soulSand = findNearestSoulSand();
                float targetYaw = mc.player.getYaw();
                float targetPitch = 90.0F;
                if (soulSand != null) {
                    Vec3d center = Vec3d.ofCenter(soulSand);
                    targetYaw = (float) Rotations.getYaw(center);
                    targetPitch = (float) Rotations.getPitch(center);
                }
                mc.player.refreshPositionAndAngles(centerX, mc.player.getY(), centerZ, targetYaw, targetPitch);
                if (mc.getNetworkHandler() != null)
                    mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(centerX, mc.player.getY(), centerZ, targetYaw, targetPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
                pearlCount = InvUtils.find(Items.ENDER_PEARL).count();
                state = State.THROW;
                timer = 10;
            }
            case THROW -> {
                FindItemResult pearl = InvUtils.find(Items.ENDER_PEARL);
                if (pearl.found()) {
                    if (!pearl.isHotbar()) InvUtils.move().from(pearl.slot()).toHotbar(mc.player.getInventory().selectedSlot);
                    double centerX = Math.floor(mc.player.getX()) + 0.5;
                    double centerZ = Math.floor(mc.player.getZ()) + 0.5;
                    BlockPos soulSand = findNearestSoulSand();
                    float targetYaw = mc.player.getYaw();
                    float targetPitch = 90.0F;
                    if (soulSand != null) {
                        Vec3d center = Vec3d.ofCenter(soulSand);
                        targetYaw = (float) Rotations.getYaw(center);
                        targetPitch = (float) Rotations.getPitch(center);
                    }
                    mc.player.setVelocity(0, mc.player.getVelocity().y, 0);
                    mc.player.setSprinting(false);
                    mc.options.forwardKey.setPressed(false);
                    mc.options.backKey.setPressed(false);
                    mc.options.leftKey.setPressed(false);
                    mc.options.rightKey.setPressed(false);
                    mc.player.refreshPositionAndAngles(centerX, mc.player.getY(), centerZ, targetYaw, targetPitch);
                    if (mc.getNetworkHandler() != null)
                        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(centerX, mc.player.getY(), centerZ, targetYaw, targetPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
                    InvUtils.swap(pearl.slot(), true);
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    InvUtils.swapBack();
                    if (InvUtils.find(Items.ENDER_PEARL).count() < pearlCount) {
                        info("Pearl launched.");
                        state = State.GOTO_OBS;
                        timer = 20;
                    } else {
                        info("Re-attempting launch...");
                        timer = 10;
                    }
                } else {
                    error("No pearls found. Back to waiting.");
                    state = State.WAIT_TP;
                }
            }
            case GOTO_OBS -> { if (walkTo(Vec3d.ofCenter(obsidianChestPos.get()), 2)) { state = State.OPEN_OBS; timer = 0; } }
            case OPEN_OBS -> { lookAndOpen(obsidianChestPos.get(), () -> { state = State.LOOT_OBS; timer = 20; }); timer = 100; }
            case LOOT_OBS -> {
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    lootShulkers(Items.OBSIDIAN, obsidianToTake.get());
                    mc.player.closeHandledScreen();
                    state = State.GOTO_CRY;
                    timer = 20;
                } else if (timer <= 0) {
                    state = State.OPEN_OBS;
                }
            }
            case GOTO_CRY -> { if (walkTo(Vec3d.ofCenter(cryingChestPos.get()), 2)) { state = State.OPEN_CRY; timer = 0; } }
            case OPEN_CRY -> { lookAndOpen(cryingChestPos.get(), () -> { state = State.LOOT_CRY; timer = 20; }); timer = 100; }
            case LOOT_CRY -> {
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    lootShulkers(Items.CRYING_OBSIDIAN, cryingToTake.get());
                    mc.player.closeHandledScreen();
                    state = State.RETURN;
                    timer = 20;
                } else if (timer <= 0) {
                    state = State.OPEN_CRY;
                }
            }
            case RETURN -> { stop(); sendMessage("/home " + homeBaseName.get()); toggle(); }
            default -> {}
        }
    }

    private void tickKitbot() {
        switch (state) {
            case KIT_MOVE_TO_SAFE -> {
                BlockPos safeCenter = findSafeChunkCenter();
                if (safeCenter != null) {
                    if (walkTo(Vec3d.ofCenter(safeCenter), 2)) {
                        info("Arrived at safe position. Waiting...");
                        state = State.KIT_WAIT;
                        timer = 100;
                    }
                } else {
                    warning("No safe chunk found nearby. Waiting here...");
                    state = State.KIT_WAIT;
                    timer = 100;
                }
            }
            case KIT_WAIT -> { state = State.KIT_SEND; }
            case KIT_SEND -> {
                LogoBuilder logoBuilder = Modules.get().get(LogoBuilder.class);
                String cmd = obsidianKitCommand.get();
                if (logoBuilder != null && logoBuilder.neededMaterial != null) {
                    if (logoBuilder.neededMaterial == Items.CRYING_OBSIDIAN) {
                        cmd = cryingKitCommand.get();
                        info("Crying obsidian needed. Using: " + cmd);
                    } else if (logoBuilder.neededMaterial == Items.NETHERITE_BLOCK) {
                        cmd = netheriteKitCommand.get();
                        info("Netherite block needed. Using: " + cmd);
                    } else {
                        info("Obsidian needed. Using: " + cmd);
                    }
                } else {
                    info("No material detected from LogoBuilder. Defaulting to obsidian command.");
                }
                if (cmd.contains("(kitbotname)")) cmd = cmd.replace("(kitbotname)", kitbotName.get());
                sendMessage(cmd);
                tpaAccepted = false;
                state = State.KIT_CHECK;
                timer = 100;
                kitRetryTimer = restockDelay.get() * 60 * 20;
            }
            case KIT_CHECK -> {
                targetItem = findDroppedShulker();
                if (targetItem != null) { shulkerTargetPos = targetItem.getBlockPos(); state = State.KIT_PICKUP; return; }
                if (countShulkers() > initialShulkerCount || shulkerPickedUp) {
                    info("Restock complete. Returning to work...");
                    finishRestock();
                    return;
                }
                if (repeatUntilFound.get() && !tpaAccepted) {
                    if (kitRetryTimer > 0) kitRetryTimer--;
                    else { info("Retrying command..."); state = State.KIT_SEND; }
                }
            }
            case KIT_PICKUP -> {
                if (targetItem == null || !targetItem.isAlive()) { state = State.KIT_CHECK; return; }
                if (walkTo(targetItem.getPos(), 0)) {
                    if (timer <= 0) {
                        shulkerPickedUp = true;
                        timer = 10;
                        state = State.KIT_CHECK;
                        shulkerTargetPos = null;
                        targetItem = null;
                    }
                }
            }
            default -> {}
        }
    }

    private void finishRestock() {
        Module logoBuilder = Modules.get().get(LogoBuilder.class);
        if (logoBuilder != null && !logoBuilder.isActive()) logoBuilder.toggle();
        if (isActive()) toggle();
    }

    private ItemEntity findDroppedShulker() {
        int pCX = mc.player.getBlockPos().getX() >> 4;
        int pCZ = mc.player.getBlockPos().getZ() >> 4;
        double pY = mc.player.getY();
        ItemEntity closest = null;
        double minDist = Double.MAX_VALUE;
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof ItemEntity item && entity.isAlive()) {
                if ((entity.getBlockX() >> 4) == pCX && (entity.getBlockZ() >> 4) == pCZ && Math.abs(entity.getY() - pY) < 2.0) {
                    if (Block.getBlockFromItem(item.getStack().getItem()) instanceof ShulkerBoxBlock) {
                        double dist = mc.player.squaredDistanceTo(entity);
                        if (dist < minDist) { minDist = dist; closest = item; }
                    }
                }
            }
        }
        return closest;
    }

    private BlockPos findSafeChunkCenter() {
        int pCX = mc.player.getBlockPos().getX() >> 4;
        int pCZ = mc.player.getBlockPos().getZ() >> 4;
        int pY = mc.player.getBlockPos().getY();
        int r = kitSafeRadius.get();
        for (int dist = 0; dist <= r; dist++) {
            for (int rx = -dist; rx <= dist; rx++) {
                for (int rz = -dist; rz <= dist; rz++) {
                    if (Math.max(Math.abs(rx), Math.abs(rz)) == dist) {
                        int cx = pCX + rx, cz = pCZ + rz;
                        if (isChunkSolidAtY(cx, cz, pY - 1)) return new BlockPos((cx << 4) + 8, pY, (cz << 4) + 8);
                    }
                }
            }
        }
        return null;
    }

    private boolean isChunkSolidAtY(int cx, int cz, int y) {
        int minX = cx << 4, minZ = cz << 4;
        for (int x = minX; x < minX + 16; x++)
            for (int z = minZ; z < minZ + 16; z++)
                if (mc.world.getBlockState(new BlockPos(x, y, z)).isAir()) return false;
        return true;
    }

    private int countShulkers() {
        int count = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            if (Block.getBlockFromItem(mc.player.getInventory().getStack(i).getItem()) instanceof ShulkerBoxBlock) count++;
        }
        return count;
    }

    private boolean walkTo(Vec3d target, int radius) {
        BlockPos goalPos = BlockPos.ofFloored(target);
        double dist = Math.sqrt(mc.player.squaredDistanceTo(target.x, mc.player.getY(), target.z));
        if (useBaritone.get()) {
            if (!goalPos.equals(lastBaritoneGoal)) {
                stopBaritone();
                try {
                    Class<?> baritoneAPI = Class.forName("baritone.api.BaritoneAPI");
                    Object provider = baritoneAPI.getMethod("getProvider").invoke(null);
                    Object primary = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
                    Object customGoalProcess = primary.getClass().getMethod("getCustomGoalProcess").invoke(primary);
                    Class<?> goalNear = Class.forName("baritone.api.pathing.goals.GoalNear");
                    Object goal = goalNear.getConstructor(BlockPos.class, int.class).newInstance(goalPos, radius);
                    customGoalProcess.getClass().getMethod("setGoalAndPath", Class.forName("baritone.api.pathing.goals.Goal")).invoke(customGoalProcess, goal);
                    lastBaritoneGoal = goalPos;
                } catch (Exception e) { return manualWalk(target, radius); }
            }
            if (dist < (radius + 0.5)) { stop(); return true; }
            return false;
        }
        return manualWalk(target, radius);
    }

    private boolean manualWalk(Vec3d target, int radius) {
        double dist = Math.sqrt(mc.player.squaredDistanceTo(target.x, mc.player.getY(), target.z));
        if (dist < (radius + 0.2)) { stop(); return true; }
        double dx = target.x - mc.player.getX(), dz = target.z - mc.player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
        mc.player.setYaw(yaw);
        Rotations.rotate(yaw, mc.player.getPitch());
        mc.options.forwardKey.setPressed(true);
        return false;
    }

    private void stop() {
        mc.options.forwardKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        if (mc.player != null) mc.player.setVelocity(0, mc.player.getVelocity().y, 0);
        lastBaritoneGoal = null;
    }

    private void stopBaritone() {
        lastBaritoneGoal = null;
        try {
            Class<?> baritoneAPI = Class.forName("baritone.api.BaritoneAPI");
            Object provider = baritoneAPI.getMethod("getProvider").invoke(null);
            Object primary = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object customGoalProcess = primary.getClass().getMethod("getCustomGoalProcess").invoke(primary);
            customGoalProcess.getClass().getMethod("stop").invoke(customGoalProcess);
        } catch (Exception ignored) {}
    }

    private void lookAndOpen(BlockPos pos, Runnable callback) {
        stop();
        Vec3d interactionPoint = Vec3d.ofCenter(pos).add(0, 0.45, 0);
        Rotations.rotate(Rotations.getYaw(interactionPoint), Rotations.getPitch(interactionPoint), 5, () -> {
            if (mc.interactionManager != null && mc.player != null) {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(interactionPoint, Direction.UP, pos, false));
                mc.player.swingHand(Hand.MAIN_HAND);
            }
            if (callback != null) callback.run();
        });
    }

    private void lootShulkers(Item material, int targetCount) {
        GenericContainerScreen screen = (GenericContainerScreen) mc.currentScreen;
        int size = screen.getScreenHandler().getInventory().size() - 36;
        int currentCount = 0;
        for (int i = 0; i < 36; i++) {
            if (isShulkerWith(mc.player.getInventory().getStack(i), material)) currentCount++;
        }
        int toTake = targetCount - currentCount;
        if (toTake <= 0) return;
        int taken = 0;
        for (int i = 0; i < size && taken < toTake; i++) {
            ItemStack stack = screen.getScreenHandler().getSlot(i).getStack();
            if (isShulkerWith(stack, material)) {
                mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                taken++;
            }
        }
    }

    private boolean isShulkerWith(ItemStack stack, Item material) {
        if (!(Block.getBlockFromItem(stack.getItem()) instanceof ShulkerBoxBlock)) return false;
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container == null) return false;
        for (ItemStack inner : container.iterateNonEmpty()) {
            if (inner.getItem() == material) return true;
        }
        return false;
    }

    private BlockPos findNearestSoulSand() {
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (int x = -5; x <= 5; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -5; z <= 5; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (mc.world.getBlockState(pos).isOf(Blocks.SOUL_SAND)) {
                        double dist = mc.player.squaredDistanceTo(Vec3d.ofCenter(pos));
                        if (dist < nearestDist) { nearestDist = dist; nearest = pos; }
                    }
                }
            }
        }
        return nearest;
    }
}
