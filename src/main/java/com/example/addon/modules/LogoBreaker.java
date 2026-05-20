package com.example.addon.modules;

import com.example.addon.GaBausSkyLogoBuilder;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.util.*;

public class LogoBreaker extends Module {

    private final SettingGroup sgGeneral    = settings.getDefaultGroup();
    private final SettingGroup sgLitematica = settings.createGroup("Litematica");
    private final SettingGroup sgMovement   = settings.createGroup("Movement");
    private final SettingGroup sgSafety     = settings.createGroup("Safety");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("break-range").defaultValue(4.5).sliderMax(6).build());

    private final Setting<Integer> scanRange = sgGeneral.add(new IntSetting.Builder()
        .name("scan-radius").defaultValue(32).min(5).sliderMax(128).build());

    private final Setting<List<Block>> targetBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("target-blocks")
        .description("Blocks to break in normal mode.")
        .defaultValue(List.of(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN))
        .build());

    private final Setting<Integer> eatThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("eat-threshold")
        .description("Hunger level to start eating (4 muslitos = 8).")
        .defaultValue(8).min(1).max(20).sliderMax(20).build());

    private final Setting<Double> healthThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("health-threshold")
        .description("Minimum health to eat (20 = full health).")
        .defaultValue(15.0).min(1).max(20).sliderMax(20).build());

    private final Setting<List<Item>> foodItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("food-items")
        .description("Items to eat or avoid.")
        .build());

    private final Setting<Boolean> foodWhitelist = sgGeneral.add(new BoolSetting.Builder()
        .name("food-whitelist")
        .description("If enabled, only items in the list will be eaten. Otherwise, it avoids them.")
        .defaultValue(true).build());

    private final Setting<Boolean> searchFarChunks = sgGeneral.add(new BoolSetting.Builder()
        .name("search-far-chunks")
        .description("If no blocks are found, move straight with Baritone to find new chunks.")
        .defaultValue(false).build());

    private final Setting<Boolean> litematicaMode = sgLitematica.add(new BoolSetting.Builder()
        .name("litematica-mode")
        .description("Use the loaded Litematica schematic to decide what to break.")
        .defaultValue(false)
        .build());

    private final Setting<List<Block>> schematicBreakFilter = sgLitematica.add(new BlockListSetting.Builder()
        .name("schematic-break-filter")
        .description("Only break these block types when they are incorrect. Leave empty to break all wrong blocks.")
        .defaultValue(List.of())
        .build());

    private final Setting<Boolean> breakForAir = sgLitematica.add(new BoolSetting.Builder()
        .name("break-for-air")
        .description("Break blocks that should be air according to the schematic.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> breakWrongType = sgLitematica.add(new BoolSetting.Builder()
        .name("break-wrong-type")
        .description("Break blocks that are the wrong type according to the schematic.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> chunkMode = sgMovement.add(new BoolSetting.Builder()
        .name("chunk-mode").description("Focus on one chunk at a time.")
        .defaultValue(true).build());

    private final Setting<Boolean> autoDisconnect = sgSafety.add(new BoolSetting.Builder()
        .name("auto-disconnect")
        .description("Automatically disconnects if you pop a totem or die.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> pauseOnMob = sgSafety.add(new BoolSetting.Builder()
        .name("pause-on-mob")
        .description("Pause breaking when a mob is within 4 blocks on X/Z. Let KillAura handle it.")
        .defaultValue(true)
        .build());

    private static final boolean USE_BARITONE  = true;
    private static final int     STUCK_TICKS   = 80;
    private static final double  STUCK_DIST_SQ = 0.05 * 0.05;
    private static final int     GOAL_COOLDOWN = 40;

    private enum State { BREAKING, EATING }
    private State state       = State.BREAKING;
    private State preEatState = State.BREAKING;

    private int     oldSlotBeforeEating = -1;
    private int     foodLevelAtStart    = -1;
    private boolean isAutoDisconnecting = false;

    private Method  getSchematicWorldMethod = null;
    private Method  getBlockStateMethod     = null;
    private boolean reflectionReady         = false;

    private final List<BlockPos> snakeQueue = new ArrayList<>();
    private int snakeIndex = 0;

    private int     currentRowZ   = Integer.MAX_VALUE;
    private boolean rowPositioned = false;

    private int activeChunkX = Integer.MAX_VALUE, activeChunkZ = Integer.MAX_VALUE;

    private BlockPos lockedBaritoneGoal = null;
    private int      goalChangeCooldown = 0;
    private Vec3d    lastPlayerPos      = null;
    private int      baritoneStuckTimer = 0;
    private int      baritoneFailCount  = 0;
    private int      baritoneRetryDelay = 0;

    private float searchYaw = -1;
    private int   timer     = 0;

    public LogoBreaker() {
        super(GaBausSkyLogoBuilder.CATEGORY, "logo-breaker", "Logo Breaker");
    }

    @Override
    public void onActivate() {
        snakeQueue.clear();
        snakeIndex         = 0;
        currentRowZ        = Integer.MAX_VALUE;
        rowPositioned      = false;
        lockedBaritoneGoal = null;
        lastPlayerPos      = null;
        baritoneStuckTimer = baritoneRetryDelay = baritoneFailCount = 0;
        goalChangeCooldown = 0;
        activeChunkX = activeChunkZ = Integer.MAX_VALUE;
        timer = 0;
        state = State.BREAKING;
        isAutoDisconnecting = false;
        searchYaw = -1;
        initLitematicaReflection();
    }

    @Override
    public void onDeactivate() {
        stopBaritone();
        mc.options.forwardKey.setPressed(false);
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (!autoDisconnect.get()) return;
        if (event.packet instanceof EntityStatusS2CPacket packet) {
            if (packet.getEntity(mc.world) == mc.player) {
                if (packet.getStatus() == 35) {
                    isAutoDisconnecting = true;
                    toggle();
                    mc.player.networkHandler.getConnection().disconnect(
                        net.minecraft.text.Text.literal("[LogoBreaker] Totem pop detected. Disconnecting..."));
                } else if (packet.getStatus() == 3) {
                    isAutoDisconnecting = true;
                    toggle();
                    mc.player.networkHandler.getConnection().disconnect(
                        net.minecraft.text.Text.literal("[LogoBreaker] Death detected (packet). Disconnecting..."));
                }
            }
        }
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!autoDisconnect.get()) return;
        if (event.screen instanceof net.minecraft.client.gui.screen.DeathScreen) {
            isAutoDisconnecting = true;
            toggle();
            mc.player.networkHandler.getConnection().disconnect(
                net.minecraft.text.Text.literal("[LogoBreaker] Death screen detected. Disconnecting..."));
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (autoDisconnect.get() && (mc.player.getHealth() <= 0 || mc.player.isDead())) {
            isAutoDisconnecting = true;
            toggle();
            mc.player.networkHandler.getConnection().disconnect(
                net.minecraft.text.Text.literal("[LogoBreaker] Death detected. Disconnecting..."));
            return;
        }

        timer++;
        if (goalChangeCooldown > 0) goalChangeCooldown--;
        if (baritoneRetryDelay > 0) baritoneRetryDelay--;

        Vec3d currentPos = mc.player.getPos();
        if (lastPlayerPos != null && lockedBaritoneGoal != null) {
            if (currentPos.squaredDistanceTo(lastPlayerPos) < STUCK_DIST_SQ)
                baritoneStuckTimer++;
            else
                baritoneStuckTimer = 0;

            if (baritoneStuckTimer >= STUCK_TICKS) {
                baritoneStuckTimer = 0;
                baritoneFailCount++;
                stopBaritone();
                lockedBaritoneGoal = null;
                goalChangeCooldown = GOAL_COOLDOWN;
                baritoneRetryDelay = 20;
            }
        }
        lastPlayerPos = currentPos;

        if (state != State.EATING) {
            boolean lowHunger = mc.player.getHungerManager().getFoodLevel() <= eatThreshold.get();
            boolean lowHealth = mc.player.getHealth() <= healthThreshold.get();
            if (lowHunger || lowHealth) {
                FindItemResult food = InvUtils.find(this::isFoodValid);
                if (food.found()) {
                    preEatState = state;
                    state = State.EATING;
                    foodLevelAtStart = mc.player.getHungerManager().getFoodLevel();
                    stopBaritone();
                    oldSlotBeforeEating = mc.player.getInventory().selectedSlot;
                }
            }
        }

        if (state == State.EATING) {
            doEating();
            return;
        }

        if (snakeQueue.isEmpty() || snakeIndex >= snakeQueue.size()) {
            if (timer % 10 == 0) rebuildQueue();
            return;
        }

        tickSnake();
    }

    private void doEating() {
        if (mc.player.getHungerManager().getFoodLevel() >= 20
                || (mc.player.getHealth() >= 20
                    && mc.player.getHungerManager().getFoodLevel() > foodLevelAtStart)) {
            mc.options.useKey.setPressed(false);
            mc.interactionManager.stopUsingItem(mc.player);
            if (oldSlotBeforeEating != -1) InvUtils.swap(oldSlotBeforeEating, false);
            state = preEatState;
            foodLevelAtStart = -1;
            return;
        }

        FindItemResult food = InvUtils.find(this::isFoodValid);
        if (!food.found()) {
            state = preEatState;
            foodLevelAtStart = -1;
            return;
        }

        int slot = ensureInHotbar(food);
        InvUtils.swap(slot, false);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        mc.options.useKey.setPressed(true);
    }

    private boolean isFoodValid(ItemStack stack) {
        if (!stack.contains(DataComponentTypes.FOOD)) return false;
        Item item = stack.getItem();
        boolean contains = foodItems.get().contains(item);
        return foodWhitelist.get() ? contains : !contains;
    }

    private void initLitematicaReflection() {
        try {
            Class<?> swh = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
            try {
                getSchematicWorldMethod = swh.getDeclaredMethod("getSchematicWorld");
            } catch (NoSuchMethodException e) {
                for (Method m : swh.getDeclaredMethods()) {
                    if (m.getReturnType().getName().contains("WorldSchematic") && m.getParameterCount() == 0) {
                        getSchematicWorldMethod = m;
                        break;
                    }
                }
            }
            if (getSchematicWorldMethod != null) {
                getSchematicWorldMethod.setAccessible(true);
                reflectionReady = true;
            } else {
                warning("LogoBreaker: Litematica world getter not found.");
            }
        } catch (Exception ignored) {}
    }

    private Object getSchematicWorld() {
        if (!reflectionReady || getSchematicWorldMethod == null) return null;
        try { return getSchematicWorldMethod.invoke(null); }
        catch (Exception e) { return null; }
    }

    private BlockState getSchematicState(Object schWorld, BlockPos pos) {
        if (schWorld == null) return null;
        try {
            if (getBlockStateMethod == null) {
                for (Method m : schWorld.getClass().getMethods()) {
                    if (m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == BlockPos.class
                            && m.getReturnType() == BlockState.class) {
                        getBlockStateMethod = m;
                        getBlockStateMethod.setAccessible(true);
                        break;
                    }
                }
            }
            if (getBlockStateMethod == null) return null;
            return (BlockState) getBlockStateMethod.invoke(schWorld, pos);
        } catch (Exception e) { return null; }
    }

    private boolean shouldBreak(BlockPos pos, Object schWorld) {
        BlockState worldState = mc.world.getBlockState(pos);

        if (!litematicaMode.get()) {
            return isTargetBlock(pos, targetBlocks.get());
        }

        if (worldState.isAir()) return false;

        List<Block> filter = schematicBreakFilter.get();
        if (!filter.isEmpty() && !filter.contains(worldState.getBlock())) return false;

        BlockState schState = getSchematicState(schWorld, pos);
        boolean schIsAir = (schState == null || schState.isAir());

        if (schIsAir) return breakForAir.get();
        return breakWrongType.get() && schState.getBlock() != worldState.getBlock();
    }

    private void rebuildQueue() {
        BlockPos pPos = mc.player.getBlockPos();
        int r = scanRange.get();
        Set<BlockPos> allTargets = new HashSet<>();

        Object schWorld = litematicaMode.get() ? getSchematicWorld() : null;
        if (litematicaMode.get() && schWorld == null) {
            if (timer % 100 == 0) warning("No Litematica schematic loaded.");
            return;
        }

        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos pos = pPos.add(x, dy, z);
                    if (shouldBreak(pos, schWorld)) {
                        allTargets.add(pos);
                        if (!litematicaMode.get()) break;
                    }
                }
            }
        }

        if (allTargets.isEmpty()) {
            activeChunkX = Integer.MAX_VALUE;
            if (searchFarChunks.get()) moveStraight();
            return;
        }

        Set<BlockPos> targets = allTargets;
        if (chunkMode.get()) {
            boolean currentOk = allTargets.stream()
                .anyMatch(p -> (p.getX() >> 4) == activeChunkX && (p.getZ() >> 4) == activeChunkZ);

            if (activeChunkX == Integer.MAX_VALUE || !currentOk) {
                BlockPos closest = allTargets.stream()
                    .min(Comparator.comparingDouble(p ->
                        mc.player.getPos().squaredDistanceTo(Vec3d.ofCenter(p))))
                    .get();
                activeChunkX = closest.getX() >> 4;
                activeChunkZ = closest.getZ() >> 4;
                baritoneFailCount  = 0;
                lockedBaritoneGoal = null;
                searchYaw = -1;
            }

            Set<BlockPos> chunkTargets = new HashSet<>();
            for (BlockPos p : allTargets)
                if ((p.getX() >> 4) == activeChunkX && (p.getZ() >> 4) == activeChunkZ)
                    chunkTargets.add(p);

            if (chunkTargets.isEmpty()) { activeChunkX = Integer.MAX_VALUE; return; }
            targets = chunkTargets;
        }

        snakeQueue.clear();
        snakeQueue.addAll(buildSnakeOrder(targets));
        snakeIndex = 0;
    }

    private void moveStraight() {
        if (lockedBaritoneGoal != null) return;

        try {
            Object schWorld = getSchematicWorld();

            if (searchYaw == -1)
                searchYaw = (float) (Math.round(mc.player.getYaw() / 90.0) * 90.0);

            if (isImmediateAreaEmpty(schWorld, searchYaw)) {
                searchYaw += 90;
                return;
            }

            double rad = Math.toRadians(searchYaw);
            double dx  = -Math.sin(rad) * 2.0;
            double dz  =  Math.cos(rad) * 2.0;
            BlockPos blindGoal = mc.player.getBlockPos()
                .add((int) Math.round(dx), 0, (int) Math.round(dz));

            if (hasAnyBlockInColumn(schWorld, blindGoal)) {
                moveTowards(blindGoal, 0);
            } else {
                searchYaw += 90;
            }
        } catch (Exception ignored) {}
    }

    private boolean isImmediateAreaEmpty(Object schWorld, float yaw) throws Exception {
        double rad = Math.toRadians(yaw);
        double dx  = -Math.sin(rad);
        double dz  =  Math.cos(rad);
        BlockPos pPos = mc.player.getBlockPos();

        for (int i = 1; i <= 2; i++) {
            BlockPos checkPos = pPos.add((int) (dx * i), 0, (int) (dz * i));
            if (hasAnyBlockInColumn(schWorld, checkPos)) return false;
        }
        return true;
    }

    private boolean hasAnyBlockInColumn(Object schWorld, BlockPos pos) throws Exception {
        for (int y = -10; y <= 10; y++) {
            BlockPos check = pos.up(y);
            if (litematicaMode.get() && schWorld != null) {
                BlockState s = getSchematicState(schWorld, check);
                if (s != null && !s.isAir()) return true;
            } else {
                if (isTargetBlock(check, targetBlocks.get())) return true;
            }
        }
        return false;
    }

    private List<BlockPos> buildSnakeOrder(Set<BlockPos> blocks) {
        Map<Integer, List<BlockPos>> byZ = new TreeMap<>();
        for (BlockPos b : blocks)
            byZ.computeIfAbsent(b.getZ(), k -> new ArrayList<>()).add(b);

        List<BlockPos> ordered = new ArrayList<>();
        boolean leftToRight = true;
        for (List<BlockPos> row : byZ.values()) {
            if (leftToRight) row.sort(Comparator.comparingInt(BlockPos::getX));
            else             row.sort(Comparator.comparingInt(BlockPos::getX).reversed());
            ordered.addAll(row);
            leftToRight = !leftToRight;
        }
        return ordered;
    }

    private boolean isMobNearby() {
        if (!pauseOnMob.get()) return false;
        double px = mc.player.getX();
        double pz = mc.player.getZ();
        for (var entity : mc.world.getEntities()) {
            if (!(entity instanceof MobEntity mob)) continue;
            double dx = mob.getX() - px;
            double dz = mob.getZ() - pz;
            if (Math.sqrt(dx * dx + dz * dz) <= 5.0) return true;
        }
        return false;
    }

    private void tickSnake() {
        Object schWorld = litematicaMode.get() ? getSchematicWorld() : null;

        while (snakeIndex < snakeQueue.size() && !shouldBreak(snakeQueue.get(snakeIndex), schWorld))
            snakeIndex++;

        if (snakeIndex >= snakeQueue.size()) return;

        BlockPos target  = snakeQueue.get(snakeIndex);
        double   brRange = range.get();

        if (target.getZ() != currentRowZ) {
            currentRowZ   = target.getZ();
            rowPositioned = false;
        }

        if (!rowPositioned) {
            int playerZ = mc.player.getBlockPos().getZ();
            int playerX = mc.player.getBlockPos().getX();
            int playerY = mc.player.getBlockPos().getY();

            if (playerZ == currentRowZ) {
                int safeZ = Integer.MAX_VALUE;
                outer:
                for (int radius = 1; radius <= 3; radius++) {
                    for (int tryZ : new int[]{ currentRowZ - radius, currentRowZ + radius }) {
                        BlockPos floor = new BlockPos(playerX, playerY - 1, tryZ);
                        BlockPos feet  = new BlockPos(playerX, playerY,     tryZ);
                        BlockPos head  = new BlockPos(playerX, playerY + 1, tryZ);
                        if (!mc.world.getBlockState(floor).isAir()
                                && mc.world.getBlockState(feet).isAir()
                                && mc.world.getBlockState(head).isAir()) {
                            safeZ = tryZ;
                            break outer;
                        }
                    }
                }

                if (safeZ != Integer.MAX_VALUE) {
                    BlockPos safeGoal = new BlockPos(playerX, playerY, safeZ);
                    if (mc.player.getPos().distanceTo(Vec3d.ofCenter(safeGoal)) <= 1.0) {
                        stopBaritone();
                        mc.options.forwardKey.setPressed(false);
                        rowPositioned = true;
                    } else {
                        moveTowards(safeGoal, 0);
                    }
                } else {
                    rowPositioned = true;
                }
                return;
            }

            rowPositioned = true;
            stopBaritone();
            mc.options.forwardKey.setPressed(false);
        }

        if (mc.player.getBlockPos().getZ() == currentRowZ) {
            rowPositioned = false;
            return;
        }

        double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(target));

        if (dist <= brRange) {
            stopBaritone();
            mc.options.forwardKey.setPressed(false);
            baritoneStuckTimer = baritoneFailCount = 0;
            goalChangeCooldown = 0;

            tryBreakPathObstacle(target, schWorld);

            if (isMobNearby()) return;

            FindItemResult pick = InvUtils.find(s -> s.getItem() instanceof PickaxeItem);
            if (!pick.found()) { error("No pickaxe found!"); toggle(); return; }
            InvUtils.swap(ensureInHotbar(pick), false);

            Vec3d center = Vec3d.ofCenter(target);
            Rotations.rotate(Rotations.getYaw(center), Rotations.getPitch(center), () -> {
                mc.interactionManager.updateBlockBreakingProgress(target, Direction.UP);
                mc.player.swingHand(Hand.MAIN_HAND);
            });
        } else {
            moveTowards(target, (int) Math.floor(brRange - 1.5));
        }
    }

    private void tryBreakPathObstacle(BlockPos currentTarget, Object schWorld) {
        if (snakeIndex + 1 >= snakeQueue.size()) return;

        BlockPos next = snakeQueue.get(snakeIndex + 1);
        int dx = Integer.signum(next.getX() - currentTarget.getX());
        int dz = Integer.signum(next.getZ() - currentTarget.getZ());
        if (dx == 0 && dz == 0) return;

        BlockPos playerPos = mc.player.getBlockPos();
        for (int step = 1; step <= 2; step++) {
            BlockPos between = new BlockPos(
                currentTarget.getX() + dx * step,
                playerPos.getY(),
                currentTarget.getZ() + dz * step);

            var state = mc.world.getBlockState(between);
            if (!state.isAir() && !shouldBreak(between, schWorld)) {
                double d = mc.player.getPos().distanceTo(Vec3d.ofCenter(between));
                if (d <= range.get()) {
                    Vec3d c = Vec3d.ofCenter(between);
                    Rotations.rotate(Rotations.getYaw(c), Rotations.getPitch(c), () -> {
                        mc.interactionManager.updateBlockBreakingProgress(between, Direction.UP);
                        mc.player.swingHand(Hand.MAIN_HAND);
                    });
                }
                break;
            }
        }
    }

    private boolean isTargetBlock(BlockPos pos, List<Block> blocks) {
        return blocks.contains(mc.world.getBlockState(pos).getBlock());
    }

    private int ensureInHotbar(FindItemResult r) {
        if (r.isHotbar()) return r.slot();
        InvUtils.move().from(r.slot()).toHotbar(mc.player.getInventory().selectedSlot);
        return mc.player.getInventory().selectedSlot;
    }

    private void moveTowards(BlockPos pos, int dist) {
        if (USE_BARITONE && baritoneFailCount < 3) {
            if (pos.equals(lockedBaritoneGoal)) return;
            if (goalChangeCooldown > 0) return;
            if (baritoneRetryDelay > 0) return;
            setBaritoneGoal(pos, dist);
        } else {
            stopBaritone();
            manualMove(pos);
        }
    }

    private void setBaritoneGoal(BlockPos pos, int dist) {
        try {
            Class<?> api = Class.forName("baritone.api.BaritoneAPI");
            Object prov  = api.getMethod("getProvider").invoke(null);
            Object prim  = prov.getClass().getMethod("getPrimaryBaritone").invoke(prov);
            Object cgp   = prim.getClass().getMethod("getCustomGoalProcess").invoke(prim);
            Class<?> gn  = Class.forName("baritone.api.pathing.goals.GoalNear");
            Object goal  = gn.getConstructor(BlockPos.class, int.class).newInstance(pos, dist);
            Class<?> gi  = Class.forName("baritone.api.pathing.goals.Goal");
            cgp.getClass().getMethod("setGoalAndPath", gi).invoke(cgp, goal);
            lockedBaritoneGoal = pos;
            goalChangeCooldown = GOAL_COOLDOWN;
            baritoneStuckTimer = 0;
        } catch (Exception e) {
            baritoneFailCount++;
            manualMove(pos);
        }
    }

    private void stopBaritone() {
        if (lockedBaritoneGoal == null) return;
        try {
            Class<?> api = Class.forName("baritone.api.BaritoneAPI");
            Object prov  = api.getMethod("getProvider").invoke(null);
            Object prim  = prov.getClass().getMethod("getPrimaryBaritone").invoke(prov);
            Object cgp   = prim.getClass().getMethod("getCustomGoalProcess").invoke(prim);
            cgp.getClass().getMethod("stop").invoke(cgp);
        } catch (Exception ignored) {}
        lockedBaritoneGoal = null;
        goalChangeCooldown = 0;
    }

    private void manualMove(BlockPos pos) {
        Vec3d t = Vec3d.ofCenter(pos), p = mc.player.getPos();
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(t.z - p.z, t.x - p.x)) - 90);
        mc.options.forwardKey.setPressed(true);
        if (t.y > p.y + 1 && mc.player.isOnGround()) mc.player.jump();
    }
}
