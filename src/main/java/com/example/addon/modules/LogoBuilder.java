package com.example.addon.modules;

import com.example.addon.GaBausSkyLogoBuilder;
import com.example.addon.mixin.InventoryAccessor;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class LogoBuilder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLogistics = settings.createGroup("Logistics");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder().name("place-range").defaultValue(4.5).sliderMax(6).build());
    private final Setting<Integer> scanRange = sgGeneral.add(new IntSetting.Builder().name("scan-radius").defaultValue(32).min(5).sliderMax(128).build());
    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder().name("place-delay").defaultValue(0).sliderMax(20).build());
    private final Setting<Boolean> useBaritone = sgGeneral.add(new BoolSetting.Builder().name("use-baritone").defaultValue(true).build());
    private final Setting<Boolean> airPlace = sgGeneral.add(new BoolSetting.Builder().name("air-place").description("If disabled, the bot will move to positions but won't place schematic blocks.").defaultValue(true).build());
    private final Setting<Boolean> chunkMode = sgGeneral.add(new BoolSetting.Builder().name("chunk-mode").description("Focus on finishing one chunk at a time.").defaultValue(true).build());
    private final Setting<Integer> eatThreshold = sgGeneral.add(new IntSetting.Builder().name("eat-threshold").description("Hunger level to start eating (4 muslitos = 8).").defaultValue(8).min(1).max(20).sliderMax(20).build());
    private final Setting<List<Item>> foodItems = sgGeneral.add(new ItemListSetting.Builder().name("food-items").description("Items to eat or avoid.").build());
    private final Setting<Boolean> foodWhitelist = sgGeneral.add(new BoolSetting.Builder().name("food-whitelist").description("If enabled, only items in the list will be eaten. Otherwise, it avoids them.").defaultValue(true).build());

    private final Setting<Integer> maxStacks = sgLogistics.add(new IntSetting.Builder().name("reload-limit-stacks").defaultValue(12).sliderMax(27).build());

    private final Setting<Boolean> autoDisconnect = sgSafety.add(new BoolSetting.Builder()
        .name("auto-disconnect")
        .description("It automatically disconnects if you take damage.")
        .defaultValue(false)
        .build()
    );

    private enum State { BUILDING, TRAVELING, OPENING, RELOADING, PLACING_STATION, WAITING_FOR_RESTOCK, EATING }
    private State state = State.BUILDING;
    private State preEatingState = State.BUILDING;

    private final List<BlockPos> toPlace = new ArrayList<>();
    private Method getSchematicWorldMethod, getBlockStateMethod;
    private boolean reflectionSuccessNotified = false;
    private int timer = 0, delayTimer = 0, waitTicks = 0, baritoneStuckTimer = 0, oldSlotBeforeEating = -1, foodLevelAtStart = -1;
    private int activeChunkX = Integer.MAX_VALUE, activeChunkZ = Integer.MAX_VALUE;
    private BlockPos targetPos, obsidianStation, cryingStation, activeStation, lastBaritoneGoal;
    public Item neededMaterial;
    private boolean isAutoDisconnecting = false;

    public LogoBuilder() {
        super(GaBausSkyLogoBuilder.CATEGORY, "logo-builder", "The logo builder");
    }

    @Override
    public void onActivate() {
        info("LogoBuilder: Activating...");
        timer = 0; delayTimer = 0; waitTicks = 0; baritoneStuckTimer = 0;
        state = State.BUILDING;
        targetPos = null;
        lastBaritoneGoal = null;
        isAutoDisconnecting = false;
        activeChunkX = Integer.MAX_VALUE;
        activeChunkZ = Integer.MAX_VALUE;
        obsidianStation = null;
        cryingStation = null;
        getSchematicWorldMethod = null;
        getBlockStateMethod = null;
        reflectionSuccessNotified = false;
        initReflection();
    }

    @Override
    public void onDeactivate() {
        stopBaritone();
        mc.options.forwardKey.setPressed(false);
        if (!isAutoDisconnecting) {
            obsidianStation = null;
            cryingStation = null;
        }
        getSchematicWorldMethod = null;
        getBlockStateMethod = null;
        reflectionSuccessNotified = false;
    }

    private void initReflection() {
        try {
            Class<?> swh = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
            // Try to find by name first, then by return type
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
            } else {
                error("Could not find Litematica world getter method.");
            }
        } catch (Exception e) {
            error("Litematica reflection error: " + e.getMessage());
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (mc.currentScreen instanceof GameMenuScreen) {
            mc.setScreen(null);
        }

        if (autoDisconnect.get() && state == State.BUILDING && mc.player.hurtTime > 0) {
            isAutoDisconnecting = true;
            mc.player.networkHandler.getConnection().disconnect(net.minecraft.text.Text.literal("[LogoBuilder] Damage detected during construction. Disconnecting..."));
            return;
        }

        if (waitTicks > 0) { waitTicks--; return; }
        if (delayTimer > 0) delayTimer--;

        if (state != State.EATING && mc.player.getHungerManager().getFoodLevel() <= eatThreshold.get()) {
            FindItemResult food = InvUtils.find(this::isFoodValid);
            if (food.found()) {
                preEatingState = state;
                state = State.EATING;
                foodLevelAtStart = mc.player.getHungerManager().getFoodLevel();
                stopBaritone();
                oldSlotBeforeEating = mc.player.getInventory().getSelectedSlot();
            }
        }

        if (lastBaritoneGoal != null && mc.player.getVelocity().horizontalLengthSquared() < 0.0001) {
            baritoneStuckTimer++;
            if (baritoneStuckTimer > 40) {
                lastBaritoneGoal = null;
                baritoneStuckTimer = 0;
            }
        } else {
            baritoneStuckTimer = 0;
        }

        if (state == State.WAITING_FOR_RESTOCK) {
            Module restock = Modules.get().get(AutoRestock.class);
            if (restock != null && !restock.isActive()) {
                state = State.BUILDING;
                info("AutoRestock finished.");
            }
            return;
        }

        if (state == State.BUILDING) {
            if (timer % 5 == 0) doScan();
            timer++;
            if (targetPos != null) {
                tickBuildingLogic();
            } else if (chunkMode.get() && activeChunkX != Integer.MAX_VALUE) {
                moveTowards(new BlockPos((activeChunkX << 4) + 8, mc.player.getBlockPos().getY(), (activeChunkZ << 4) + 8), 4);
            }
        } else if (state == State.EATING) {
            doEating();
        } else {
            doLogistics();
        }
    }

    private void doEating() {
        assert mc.player != null;
        assert mc.interactionManager != null;
        if (mc.player.getHungerManager().getFoodLevel() > foodLevelAtStart || mc.player.getHungerManager().getFoodLevel() >= 20) {
            mc.options.useKey.setPressed(false);
            mc.interactionManager.stopUsingItem(mc.player);
            if (oldSlotBeforeEating != -1) InvUtils.swap(oldSlotBeforeEating, false);
            state = preEatingState;
            foodLevelAtStart = -1;
            waitTicks = 5;
            return;
        }

        FindItemResult food = InvUtils.find(this::isFoodValid);
        if (!food.found()) {
            state = preEatingState;
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

    private void doScan() {
        try {
            if (getSchematicWorldMethod == null) {
                initReflection();
                if (getSchematicWorldMethod == null) return;
            }

            Object worldSchematic = getSchematicWorldMethod.invoke(null);
            if (worldSchematic == null) {
                if (timer % 100 == 0) warning("Litematica is connected, but no schematic world is loaded.");
                return;
            }

            if (getBlockStateMethod == null) {
                for (Method m : worldSchematic.getClass().getMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == BlockPos.class && m.getReturnType() == BlockState.class) {
                        getBlockStateMethod = m;
                        getBlockStateMethod.setAccessible(true);
                        break;
                    }
                }
            }

            if (getBlockStateMethod == null) {
                if (timer % 100 == 0) error("Could not find getBlockState method in Litematica's WorldSchematic.");
                return;
            }

            if (!reflectionSuccessNotified) {
                info("Litematica connected.");
                reflectionSuccessNotified = true;
            }

            toPlace.clear();
            assert mc.player != null;
            assert mc.world != null;
            BlockPos pPos = mc.player.getBlockPos();
            int r = scanRange.get();
            for (int x = -r; x <= r; x++) {
                for (int y = -r; y <= r; y++) {
                    for (int z = -r; z <= r; z++) {
                        BlockPos pos = pPos.add(x, y, z);
                        BlockState req = (BlockState) getBlockStateMethod.invoke(worldSchematic, pos);
                        if (req != null && !req.isAir()) {
                            BlockState current = mc.world.getBlockState(pos);
                            if (current.getBlock() != req.getBlock()) {
                                if (current.isAir() || current.isReplaceable()) {
                                    toPlace.add(pos);
                                }
                            }
                        }
                    }
                }
            }

            if (!toPlace.isEmpty()) {
                if (chunkMode.get()) {
                    boolean currentChunkInScan = false;
                    if (activeChunkX != Integer.MAX_VALUE) {
                        for (BlockPos p : toPlace) {
                            if ((p.getX() >> 4) == activeChunkX && (p.getZ() >> 4) == activeChunkZ) {
                                currentChunkInScan = true;
                                break;
                            }
                        }
                    }

                    if (activeChunkX != Integer.MAX_VALUE && !currentChunkInScan) {
                        double distToChunk = Math.sqrt(Math.pow((activeChunkX * 16 + 8) - mc.player.getX(), 2) + Math.pow((activeChunkZ * 16 + 8) - mc.player.getZ(), 2));
                        if (distToChunk < scanRange.get() - 12) {
                            info("Finished chunk: " + activeChunkX + ", " + activeChunkZ);
                            activeChunkX = Integer.MAX_VALUE;
                        }
                    }

                    if (activeChunkX == Integer.MAX_VALUE) {
                        BlockPos first = toPlace.stream()
                            .min(Comparator.comparingDouble(p -> mc.player.getEntityPos().distanceTo(Vec3d.ofCenter(p))))
                            .orElse(toPlace.get(0));
                        activeChunkX = first.getX() >> 4;
                        activeChunkZ = first.getZ() >> 4;
                        info("Switching to chunk: " + activeChunkX + ", " + activeChunkZ);
                    }

                    toPlace.removeIf(p -> (p.getX() >> 4) != activeChunkX || (p.getZ() >> 4) != activeChunkZ);
                }

                if (!toPlace.isEmpty()) {
                    toPlace.sort((a, b) -> {
                        boolean aBelow = a.getY() < mc.player.getY();
                        boolean bBelow = b.getY() < mc.player.getY();
                        if (aBelow && !bBelow) return -1;
                        if (!aBelow && bBelow) return 1;
                        return Double.compare(mc.player.getEntityPos().distanceTo(Vec3d.ofCenter(a)), mc.player.getEntityPos().distanceTo(Vec3d.ofCenter(b)));
                    });
                    targetPos = toPlace.get(0);
                } else targetPos = null;
            } else targetPos = null;
        } catch (Exception e) {
            if (timer % 100 == 0) error("Scan error: " + e.toString());
        }
    }

    private void tickBuildingLogic() {
        try {
            assert mc.player != null;
            assert mc.world != null;
            if (getSchematicWorldMethod == null || getBlockStateMethod == null) return;
            Object worldSchematic = getSchematicWorldMethod.invoke(null);
            if (worldSchematic == null) { targetPos = null; return; }

            BlockState required = (BlockState) getBlockStateMethod.invoke(worldSchematic, targetPos);
            if (required == null || required.isAir()) { targetPos = null; return; }

            Item item = required.getBlock().asItem();
            if (countItem(item) == 0) {
                info("Out of " + item.getName().getString());
                neededMaterial = item;
                activeStation = (item == Items.OBSIDIAN) ? obsidianStation : (item == Items.CRYING_OBSIDIAN ? cryingStation : null);
                stopBaritone();
                state = (activeStation == null || !(mc.world.getBlockState(activeStation).getBlock() instanceof ShulkerBoxBlock))
                        ? State.PLACING_STATION : State.TRAVELING;
                return;
            }

            double dist = mc.player.getEntityPos().distanceTo(Vec3d.ofCenter(targetPos));
            if (dist <= range.get()) {
                stopBaritone();
                mc.options.forwardKey.setPressed(false);
                if (airPlace.get() && delayTimer <= 0) {
                    if (placeBlock(targetPos, item)) {
                        delayTimer = placeDelay.get();
                    }
                }
            } else {
                moveTowards(targetPos, (int) Math.floor(range.get() - 1.2));
            }
        } catch (Exception e) {
            if (timer % 100 == 0) error("Building logic error: " + e.toString());
        }
    }

    private void doLogistics() {
        switch (state) {
            case TRAVELING -> {
                assert mc.player != null;
                assert mc.world != null;
                double dist = mc.player.getEntityPos().distanceTo(Vec3d.ofCenter(activeStation));
                if (dist <= 4.0) { stopBaritone(); state = State.OPENING; }
                else moveTowards(activeStation, 2);
            }
            case OPENING -> {
                assert mc.interactionManager != null;
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(activeStation), Direction.UP, activeStation, false));
                state = State.RELOADING; waitTicks = 15;
            }
            case RELOADING -> {
                assert mc.player != null;
                assert mc.interactionManager != null;
                if (mc.currentScreen instanceof ShulkerBoxScreen) {
                    int taken = 0;
                    for (int i = 0; i < 27 && taken < maxStacks.get(); i++) {
                        ItemStack stack = mc.player.currentScreenHandler.getSlot(i).getStack();
                        if (stack.getItem() == neededMaterial) {
                            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                            taken++;
                        }
                    }

                    boolean remaining = false;
                    for (int i = 0; i < 27; i++) {
                        if (mc.player.currentScreenHandler.getSlot(i).getStack().getItem() == neededMaterial) {
                            remaining = true;
                            break;
                        }
                    }

                    mc.player.closeHandledScreen();

                    if (!remaining) {
                        info("Shulker at " + activeStation.toShortString() + " is now empty of " + neededMaterial.getName().getString() + ". Forgetting it.");
                        if (neededMaterial == Items.OBSIDIAN) obsidianStation = null;
                        else if (neededMaterial == Items.CRYING_OBSIDIAN) cryingStation = null;
                        activeStation = null;
                    }

                    state = (countItem(neededMaterial) == 0) ? State.PLACING_STATION : State.BUILDING;
                }
            }
            case PLACING_STATION -> {
                BlockPos pos = findPlacementPos();
                if (pos != null) {
                    FindItemResult shulker = findShulkerWith(neededMaterial);
                    if (shulker.found()) {
                        int slot = ensureInHotbar(shulker);
                        if (BlockUtils.place(pos, Hand.MAIN_HAND, slot, true, 0, true, true, false)) {
                            if (neededMaterial == Items.OBSIDIAN) obsidianStation = pos;
                            else if (neededMaterial == Items.CRYING_OBSIDIAN) cryingStation = pos;
                            activeStation = pos; state = State.OPENING; waitTicks = 10;
                        }
                    } else {
                        Module restock = Modules.get().get(AutoRestock.class);
                        if (restock != null && !restock.isActive()) {
                            restock.toggle();
                            state = State.WAITING_FOR_RESTOCK;
                        } else toggle();
                    }
                }
            }
        }
    }

    private void moveTowards(BlockPos pos, int dist) {
        if (useBaritone.get()) {
            if (pos.equals(lastBaritoneGoal)) return;
            try {
                Class<?> baritoneAPI = Class.forName("baritone.api.BaritoneAPI");
                Object provider = baritoneAPI.getMethod("getProvider").invoke(null);
                Object primary = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
                Object customGoalProcess = primary.getClass().getMethod("getCustomGoalProcess").invoke(primary);

                Class<?> goalNear = Class.forName("baritone.api.pathing.goals.GoalNear");
                Object goal = goalNear.getConstructor(BlockPos.class, int.class).newInstance(pos, dist);

                Method setGoal = customGoalProcess.getClass().getMethod("setGoalAndPath", Class.forName("baritone.api.pathing.goals.Goal"));
                setGoal.invoke(customGoalProcess, goal);
                lastBaritoneGoal = pos;
            } catch (Exception e) { manualMove(pos); }
        } else manualMove(pos);
    }

    private void stopBaritone() {
        if (lastBaritoneGoal == null) return;
        try {
            Class<?> baritoneAPI = Class.forName("baritone.api.BaritoneAPI");
            Object provider = baritoneAPI.getMethod("getProvider").invoke(null);
            Object primary = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object customGoalProcess = primary.getClass().getMethod("getCustomGoalProcess").invoke(primary);
            customGoalProcess.getClass().getMethod("stop").invoke(customGoalProcess);
            lastBaritoneGoal = null;
        } catch (Exception ignored) {}
    }

    private void manualMove(BlockPos pos) {
        assert mc.player != null;
        Vec3d target = Vec3d.ofCenter(pos);
        Vec3d pPos = mc.player.getEntityPos();
        double dx = target.x - pPos.x, dz = target.z - pPos.z;
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(dz, dx)) - 90);
        mc.options.forwardKey.setPressed(true);
        if (target.y > pPos.y + 1 && mc.player.isOnGround()) mc.player.jump();
    }

    private BlockPos findPlacementPos() {
        assert mc.player != null;
        BlockPos center = mc.player.getBlockPos().down(2);
        if (isValidPlacement(center)) return center;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                BlockPos p = center.add(x, 0, z);
                if (isValidPlacement(p)) return p;
            }
        }
        return null;
    }

    private boolean isValidPlacement(BlockPos pos) {
        assert mc.world != null;
        if (!mc.world.getBlockState(pos).isReplaceable() ||
            mc.world.getBlockState(pos.up()).isReplaceable()) return false;
        assert mc.player != null;
        return mc.player.getEntityPos().distanceTo(Vec3d.ofCenter(pos)) <= range.get();
    }

    private int ensureInHotbar(FindItemResult result) {
        if (result.isHotbar()) return result.slot();
        assert mc.player != null;
        InvUtils.move().from(result.slot()).toHotbar(mc.player.getInventory().getSelectedSlot());
        return mc.player.getInventory().getSelectedSlot();
    }

    private boolean placeBlock(BlockPos pos, Item item) {
        FindItemResult result = InvUtils.find(item);
        if (!result.found()) return false;
        int slot = ensureInHotbar(result);
        int oldSlot = ((InventoryAccessor) mc.player.getInventory()).getSelectedSlot();
        InvUtils.swap(slot, false);
        boolean ok = BlockUtils.place(pos, Hand.MAIN_HAND, slot, true, 0, true, true, false);
        InvUtils.swap(oldSlot, false);
        return ok;
    }

    private int countItem(Item item) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            assert mc.player != null;
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    private FindItemResult findShulkerWith(Item material) {
        return InvUtils.find(stack -> {
            if (!(stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock() instanceof ShulkerBoxBlock)) return false;
            ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
            if (container == null) return false;
            for (ItemStack inner : container.iterateNonEmpty()) {
                if (inner.getItem() == material) return true;
            }
            return false;
        });
    }
}
