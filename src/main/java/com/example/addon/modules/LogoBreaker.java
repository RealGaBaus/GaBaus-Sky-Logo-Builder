package com.example.addon.modules;

import com.example.addon.GaBausSkyLogoBuilder;
import com.example.addon.mixin.InventoryAccessor;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.item.PickaxeItem;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class LogoBreaker extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder().name("break-range").defaultValue(4.5).sliderMax(6).build());
    private final Setting<Integer> scanRange = sgGeneral.add(new IntSetting.Builder().name("scan-radius").defaultValue(32).min(5).sliderMax(128).build());
    private final Setting<Boolean> useBaritone = sgGeneral.add(new BoolSetting.Builder().name("use-baritone").defaultValue(true).build());
    private final Setting<Boolean> chunkMode = sgGeneral.add(new BoolSetting.Builder().name("chunk-mode").description("Focus on finishing one chunk at a time.").defaultValue(true).build());
    private final Setting<Boolean> breakObsidian = sgGeneral.add(new BoolSetting.Builder().name("break-obsidian").defaultValue(true).build());
    private final Setting<Boolean> breakCryingObsidian = sgGeneral.add(new BoolSetting.Builder().name("break-crying-obsidian").defaultValue(false).build());
    private final Setting<Boolean> protectFeet = sgGeneral.add(new BoolSetting.Builder().name("protect-feet").description("Don't break blocks under your feet.").defaultValue(true).build());

    private final List<BlockPos> toBreak = new ArrayList<>();
    private int timer = 0, baritoneStuckTimer = 0;
    private int activeChunkX = Integer.MAX_VALUE, activeChunkZ = Integer.MAX_VALUE;
    private BlockPos targetPos, lastBaritoneGoal;

    public LogoBreaker() {
        super(GaBausSkyLogoBuilder.CATEGORY, "logo-breaker-beta", "Logo Breaker Beta");
    }

    @Override
    public void onActivate() {
        timer = 0; baritoneStuckTimer = 0;
        targetPos = null;
        lastBaritoneGoal = null;
        activeChunkX = Integer.MAX_VALUE;
        activeChunkZ = Integer.MAX_VALUE;
    }

    @Override
    public void onDeactivate() {
        stopBaritone();
        mc.options.forwardKey.setPressed(false);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (lastBaritoneGoal != null && mc.player.getVelocity().horizontalLengthSquared() < 0.0001) {
            baritoneStuckTimer++;
            if (baritoneStuckTimer > 40) { 
                lastBaritoneGoal = null; 
                baritoneStuckTimer = 0; 
            }
        } else {
            baritoneStuckTimer = 0;
        }

        if (timer % 5 == 0) doScan();
        timer++;

        if (targetPos != null) {
            tickBreakingLogic();
        } else if (chunkMode.get() && activeChunkX != Integer.MAX_VALUE) {
            moveTowards(new BlockPos((activeChunkX << 4) + 8, mc.player.getBlockPos().getY(), (activeChunkZ << 4) + 8), 4);
        }
    }

    private void doScan() {
        if (targetPos != null) {
            if (isBlockValid(targetPos)) return; 
            else targetPos = null; 
        }

        List<BlockPos> validBlocks = new ArrayList<>();
        BlockPos pPos = mc.player.getBlockPos();
        int r = scanRange.get();
        
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = pPos.add(x, y, z);
                    if (isBlockValid(pos)) {
                        validBlocks.add(pos);
                    }
                }
            }
        }

        if (validBlocks.isEmpty()) {
            targetPos = null;
            activeChunkX = Integer.MAX_VALUE;
            return;
        }

        if (chunkMode.get()) {
            if (activeChunkX == Integer.MAX_VALUE || validBlocks.stream().noneMatch(p -> (p.getX() >> 4) == activeChunkX && (p.getZ() >> 4) == activeChunkZ)) {
                BlockPos closest = validBlocks.stream()
                    .min(Comparator.comparingDouble(p -> mc.player.getPos().distanceTo(Vec3d.ofCenter(p))))
                    .get();
                activeChunkX = closest.getX() >> 4;
                activeChunkZ = closest.getZ() >> 4;
            }
            validBlocks.removeIf(p -> (p.getX() >> 4) != activeChunkX || (p.getZ() >> 4) != activeChunkZ);
        }

        if (validBlocks.isEmpty()) {
            targetPos = null;
            return;
        }

        validBlocks.sort(Comparator.comparingDouble(p -> mc.player.getPos().distanceTo(Vec3d.ofCenter(p))));
        targetPos = validBlocks.get(0);
    }

    private boolean isBlockValid(BlockPos pos) {
        net.minecraft.block.Block block = mc.world.getBlockState(pos).getBlock();
        boolean isCorrectType = (breakObsidian.get() && block == Blocks.OBSIDIAN) || (breakCryingObsidian.get() && block == Blocks.CRYING_OBSIDIAN);
        if (!isCorrectType) return false;

        if (protectFeet.get()) {
            Vec3d pVec = mc.player.getPos();
            BlockPos pPos = mc.player.getBlockPos();
            
            double dx = Math.abs(pos.getX() + 0.5 - pVec.x);
            double dz = Math.abs(pos.getZ() + 0.5 - pVec.z);
            if (dx * dx + dz * dz < 0.7) {
                if (pos.getY() <= pPos.getY()) return false;
            }
            
            if (!mc.player.isOnGround()) {
                Vec3d velocity = mc.player.getVelocity();
                BlockPos predictedPos = new BlockPos((int)Math.floor(pVec.x + velocity.x), (int)Math.floor(pVec.y), (int)Math.floor(pVec.z + velocity.z));
                if (pos.getX() == predictedPos.getX() && pos.getZ() == predictedPos.getZ() && pos.getY() <= predictedPos.getY()) {
                    return false;
                }
            }
        }
        return true;
    }

    private void tickBreakingLogic() {
        double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(targetPos));
        
        double breakRange = range.get();
        double moveRange = breakRange + 0.5;

        if (dist <= breakRange) {
            stopBaritone();
            mc.options.forwardKey.setPressed(false);
            
            FindItemResult pickaxe = InvUtils.find(stack -> stack.getItem() instanceof PickaxeItem);
            if (!pickaxe.found()) {
                error("No pickaxe found!");
                toggle();
                return;
            }

            int slot = ensureInHotbar(pickaxe);
            InvUtils.swap(slot, false);
            
            Vec3d center = Vec3d.ofCenter(targetPos);
            Rotations.rotate(Rotations.getYaw(center), Rotations.getPitch(center), () -> {
                mc.interactionManager.updateBlockBreakingProgress(targetPos, Direction.UP);
                mc.player.swingHand(Hand.MAIN_HAND);
            });
        } else if (dist > moveRange) {
            moveTowards(targetPos, (int) Math.floor(breakRange - 1.2));
        }
    }

    private int ensureInHotbar(FindItemResult result) {
        if (result.isHotbar()) return result.slot();
        InvUtils.move().from(result.slot()).toHotbar(mc.player.getInventory().selectedSlot);
        return mc.player.getInventory().selectedSlot;
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
        Vec3d target = Vec3d.ofCenter(pos);
        Vec3d pPos = mc.player.getPos();
        double dx = target.x - pPos.x, dz = target.z - pPos.z;
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(dz, dx)) - 90);
        mc.options.forwardKey.setPressed(true);
        if (target.y > pPos.y + 1 && mc.player.isOnGround()) mc.player.jump();
    }
}
