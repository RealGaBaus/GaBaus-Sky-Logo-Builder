package com.example.addon.modules;

import com.example.addon.GaBausSkyLogoBuilder;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
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

    private final Setting<String> helperName = sgGeneral.add(new StringSetting.Builder().name("helper-name").defaultValue("HelperName").build());
    private final Setting<String> homeBaseName = sgGeneral.add(new StringSetting.Builder().name("home-base-name").defaultValue("last-location").build());
    private final Setting<Integer> shulkersToTake = sgGeneral.add(new IntSetting.Builder().name("shulkers-to-take").defaultValue(3).min(1).sliderMax(10).build());
    private final Setting<Boolean> useBaritone = sgGeneral.add(new BoolSetting.Builder().name("use-baritone").defaultValue(true).build());
    
    private final Setting<BlockPos> obsidianChestPos = sgCoords.add(new BlockPosSetting.Builder().name("obsidian-chest").defaultValue(BlockPos.ORIGIN).build());
    private final Setting<BlockPos> cryingChestPos = sgCoords.add(new BlockPosSetting.Builder().name("crying-obsidian-chest").defaultValue(BlockPos.ORIGIN).build());

    private final Setting<Integer> pearlDelay = sgPearl.add(new IntSetting.Builder().name("pearl-delay").description("Delay in ticks after detecting the pearl before throwing it.").defaultValue(40).min(0).sliderMax(100).build());

    private enum State {
        IDLE, HOMES_DEL, HOMES_SET, ALERT, WAIT_TP, 
        LOOK_DOWN, THROW,
        GOTO_OBS, OPEN_OBS, LOOT_OBS, GOTO_CRY, OPEN_CRY, LOOT_CRY, RETURN
    }

    private State state = State.IDLE;
    private int timer = 0;
    private int pearlCount = 0;
    private BlockPos lastBaritoneGoal;

    public AutoRestock() {
        super(GaBausSkyLogoBuilder.CATEGORY, "auto-restock", "stasis restocker.");
    }

    @Override
    public void onActivate() { state = State.HOMES_DEL; timer = 0; }

    @Override
    public void onDeactivate() { stop(); }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // Auto-close Pause Menu if it opens (happens often when alt-tabbing)
        if (mc.currentScreen instanceof GameMenuScreen) {
            mc.setScreen(null);
        }

        if (timer > 0) { timer--; return; }

        switch (state) {
            case HOMES_DEL -> { mc.player.networkHandler.sendChatCommand("delhome " + homeBaseName.get()); state = State.HOMES_SET; timer = 15; }
            case HOMES_SET -> { mc.player.networkHandler.sendChatCommand("sethome " + homeBaseName.get()); state = State.ALERT; timer = 15; }
            case ALERT -> { 
                mc.player.networkHandler.sendChatCommand("msg " + helperName.get() + " #restock"); 
                state = State.WAIT_TP; 
                timer = 0; 
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
                        info("TP detected but pearl is missing. Waiting for pearl to start...");
                    }
                } else if (mc.player.age % 60 == 0) {
                     info("Waiting for TP... Distance to chest: " + (int)Math.sqrt(mc.player.squaredDistanceTo(Vec3d.ofCenter(obsidianChestPos.get()))) + " blocks.");
                }
            }
            case LOOK_DOWN -> {
                // Center the player and look down
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
                
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(centerX, mc.player.getY(), centerZ, targetYaw, targetPitch, mc.player.isOnGround()));
                }
                
                pearlCount = InvUtils.find(Items.ENDER_PEARL).count();
                state = State.THROW;
                timer = 10;
            }
            case THROW -> {
                FindItemResult pearl = InvUtils.find(Items.ENDER_PEARL);
                
                if (pearl.found()) {
                    if (!pearl.isHotbar()) {
                        InvUtils.move().from(pearl.slot()).toHotbar(mc.player.getInventory().selectedSlot);
                    }

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
                    
                    if (mc.getNetworkHandler() != null) {
                        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(centerX, mc.player.getY(), centerZ, targetYaw, targetPitch, mc.player.isOnGround()));
                    }

                    InvUtils.swap(pearl.slot(), true);
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    InvUtils.swapBack();
                    
                    int newCount = InvUtils.find(Items.ENDER_PEARL).count();
                    if (newCount < pearlCount) {
                        info("Pearl successfully launched towards the Soul Sand.");
                        state = State.GOTO_OBS;
                        timer = 20;
                    } else {
                        info("Re-attempting launch...");
                        timer = 10;
                    }
                } else {
                    error("No pearls were found! Back to waiting.");
                    state = State.WAIT_TP;
                }
            }
            case GOTO_OBS -> { if (walkTo(Vec3d.ofCenter(obsidianChestPos.get()))) { state = State.OPEN_OBS; timer = 0; } }
            case OPEN_OBS -> {
                lookAndOpen(obsidianChestPos.get(), () -> {
                    state = State.LOOT_OBS;
                    timer = 20;
                });
                timer = 100;
            }
            case LOOT_OBS -> {
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    lootShulkers(Items.OBSIDIAN);
                    mc.player.closeHandledScreen();
                    state = State.GOTO_CRY;
                    timer = 20;
                } else if (timer <= 0) {
                    state = State.OPEN_OBS;
                }
            }
            case GOTO_CRY -> { if (walkTo(Vec3d.ofCenter(cryingChestPos.get()))) { state = State.OPEN_CRY; timer = 0; } }
            case OPEN_CRY -> {
                lookAndOpen(cryingChestPos.get(), () -> {
                    state = State.LOOT_CRY;
                    timer = 20;
                });
                timer = 100;
            }
            case LOOT_CRY -> {
                if (mc.currentScreen instanceof GenericContainerScreen) {
                    lootShulkers(Items.CRYING_OBSIDIAN);
                    mc.player.closeHandledScreen();
                    state = State.RETURN;
                    timer = 20;
                } else if (timer <= 0) {
                    state = State.OPEN_CRY;
                }
            }
            case RETURN -> {
                stop();
                mc.player.networkHandler.sendChatCommand("home " + homeBaseName.get());
                toggle();
            }
        }
    }

    private boolean walkTo(Vec3d target) {
        BlockPos goalPos = BlockPos.ofFloored(target).up();
        double dist = Math.sqrt(mc.player.squaredDistanceTo(Vec3d.ofCenter(goalPos)));
        
        if (useBaritone.get()) {
            if (!goalPos.equals(lastBaritoneGoal)) {
                try {
                    Class<?> baritoneAPI = Class.forName("baritone.api.BaritoneAPI");
                    Object provider = baritoneAPI.getMethod("getProvider").invoke(null);
                    Object primary = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
                    Object customGoalProcess = primary.getClass().getMethod("getCustomGoalProcess").invoke(primary);

                    Class<?> goalBlock = Class.forName("baritone.api.pathing.goals.GoalBlock");
                    Object goal = goalBlock.getConstructor(BlockPos.class).newInstance(goalPos);

                    Method setGoalAndPath = customGoalProcess.getClass().getMethod("setGoalAndPath", Class.forName("baritone.api.pathing.goals.Goal"));
                    setGoalAndPath.invoke(customGoalProcess, goal);
                    lastBaritoneGoal = goalPos;
                } catch (Exception e) {
                    return manualWalk(Vec3d.ofCenter(goalPos));
                }
            }
            if (dist < 1.3) { stop(); return true; }
            return false;
        } else {
            return manualWalk(Vec3d.ofCenter(goalPos));
        }
    }

    private boolean manualWalk(Vec3d target) {
        double dist = Math.sqrt(mc.player.squaredDistanceTo(target.x, mc.player.getY(), target.z));
        if (dist < 0.2) { stop(); return true; }
        double dx = target.x - mc.player.getX();
        double dz = target.z - mc.player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
        mc.player.setYaw(yaw);
        Rotations.rotate(yaw, mc.player.getPitch());
        mc.options.forwardKey.setPressed(true);
        if (mc.player.horizontalCollision && mc.player.isOnGround()) mc.player.jump();
        return false;
    }

    private void stop() {
        mc.options.forwardKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        if (mc.player != null) mc.player.setVelocity(0, mc.player.getVelocity().y, 0);
        stopBaritone();
    }

    private void stopBaritone() {
        lastBaritoneGoal = null;
        try {
            Class<?> baritoneAPI = Class.forName("baritone.api.BaritoneAPI");
            Object provider = baritoneAPI.getMethod("getProvider").invoke(null);
            Object primary = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object pathingBehavior = primary.getClass().getMethod("getPathingBehavior").invoke(primary);
            pathingBehavior.getClass().getMethod("cancelEverything").invoke(pathingBehavior);
        } catch (Exception ignored) {}
    }

    private void lookAndOpen(BlockPos pos, Runnable callback) {
        stop();
        Vec3d interactionPoint = Vec3d.ofCenter(pos).add(0, 0.45, 0); // Aim at the top face
        Rotations.rotate(Rotations.getYaw(interactionPoint), Rotations.getPitch(interactionPoint), 5, () -> {
            if (mc.interactionManager != null && mc.player != null) {
                BlockHitResult hit = new BlockHitResult(interactionPoint, Direction.UP, pos, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                mc.player.swingHand(Hand.MAIN_HAND);
            }
            if (callback != null) callback.run();
        });
    }

    private void lootShulkers(Item material) {
        GenericContainerScreen screen = (GenericContainerScreen) mc.currentScreen;
        int size = screen.getScreenHandler().getInventory().size() - 36;
        
        int currentCount = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isShulkerWith(stack, material)) currentCount++;
        }

        int toTake = shulkersToTake.get() - currentCount;
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
        if (!(Block.getBlockFromItem(stack.getItem()) instanceof net.minecraft.block.ShulkerBoxBlock)) return false;
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
                        if (dist < nearestDist) {
                            nearestDist = dist;
                            nearest = pos;
                        }
                    }
                }
            }
        }
        return nearest;
    }
}