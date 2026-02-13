package com.example.addon.modules;

import com.example.addon.GaBausSkyLogoBuilder;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BaseGuardian extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAntiAfk = settings.createGroup("Anti-AFK");
    private final SettingGroup sgPearl = settings.createGroup("Pearl Precision");

    private final Setting<String> triggerCommand = sgGeneral.add(new StringSetting.Builder().name("trigger-command").defaultValue("#restock").build());
    private final Setting<List<String>> userButtons = sgGeneral.add(new StringListSetting.Builder().name("users-coords").defaultValue(List.of("Jugador1 100 64 100")).build());
    private final Setting<Integer> cooldownTicks = sgGeneral.add(new IntSetting.Builder().name("cooldown-ticks").defaultValue(40).min(0).build());
    private final Setting<Boolean> useBaritone = sgGeneral.add(new BoolSetting.Builder().name("use-baritone").defaultValue(true).build());

    private final Setting<Boolean> antiAfkEnabled = sgAntiAfk.add(new BoolSetting.Builder().name("anti-afk-enabled").defaultValue(true).build());
    private final Setting<BlockPos> posA = sgAntiAfk.add(new BlockPosSetting.Builder().name("position-a").defaultValue(BlockPos.ORIGIN).build());
    private final Setting<BlockPos> posB = sgAntiAfk.add(new BlockPosSetting.Builder().name("position-b").defaultValue(BlockPos.ORIGIN).build());

    private final Setting<Integer> pearlDelay = sgPearl.add(new IntSetting.Builder().name("pearl-delay").defaultValue(40).min(0).sliderMax(100).build());

    private enum State { IDLE, MOVING, INTERACTING, AFK_A, AFK_B, WAIT_PEARL, BUSY }

    private State state = State.IDLE;
    private final Map<BlockPos, Integer> buttonCooldowns = new ConcurrentHashMap<>();
    private final Queue<BlockPos> buttonsQueue = new LinkedList<>();
    private BlockPos currentTarget = null;
    private BlockPos lastBaritoneGoal = null;
    private int timer = 0;

    public BaseGuardian() {
        super(GaBausSkyLogoBuilder.CATEGORY, "base-guardian", "the thing that touches buttons");
    }

    @Override
    public void onActivate() {
        state = State.IDLE;
        buttonsQueue.clear();
        currentTarget = null;
        lastBaritoneGoal = null;
    }

    @Override
    public void onDeactivate() {
        stop();
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (mc.world == null || mc.player == null) return;
        String fullMsg = event.getMessage().getString();

        // If the message does not contain the trigger command, ignore it
        if (!fullMsg.contains(triggerCommand.get())) return;

        for (String entry : userButtons.get()) {
            String[] parts = entry.trim().split("\\s+");
            if (parts.length >= 4 && fullMsg.contains(parts[0])) {
                BlockPos pos = parsePos(parts[1], parts[2], parts[3]);
                if (pos != null) {
                    info("Command detected" + parts[0] + ". Adding a button to the queue: " + pos.toShortString());
                    triggerButton(pos, parts[0]);
                }
            }
        }
    }

    private void triggerButton(BlockPos pos, String user) {
        if ((!buttonCooldowns.containsKey(pos) || buttonCooldowns.get(pos) <= 0) && !buttonsQueue.contains(pos)) {
            buttonsQueue.add(pos);
            buttonCooldowns.put(pos, cooldownTicks.get());
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        // Update button cooldowns
        buttonCooldowns.forEach((pos, time) -> {
            if (time > 0) buttonCooldowns.put(pos, time - 1);
        });

        // Interrupt Anti-AFK if there is something in the queue
        if (!buttonsQueue.isEmpty() && (state == State.IDLE || state == State.AFK_A || state == State.AFK_B)) {
            info("Interrupting Anti-AFK to process button queue.");
            stop();
            state = State.WAIT_PEARL;
            return;
        }

        if (timer > 0) {
            timer--;
            return;
        }

        switch (state) {
            case IDLE -> {
                if (!buttonsQueue.isEmpty()) {
                    state = State.WAIT_PEARL;
                } else if (antiAfkEnabled.get()) {
                    state = State.AFK_A;
                }
            }
            case WAIT_PEARL -> {
                currentTarget = buttonsQueue.poll();
                if (currentTarget != null) {
                    state = State.MOVING;
                    timer = pearlDelay.get();
                } else {
                    state = State.IDLE;
                }
            }
            case MOVING -> {
                if (currentTarget == null) {
                    state = State.IDLE;
                    return;
                }
                if (walkTo(Vec3d.ofCenter(currentTarget), 2)) {
                    info("I reached the button. Initiating interaction.");
                    state = State.INTERACTING;
                }
            }
            case INTERACTING -> {
                state = State.BUSY; // Prevent the next tick from entering here again
                lookAndOpen(currentTarget, () -> {
                    info("Button activated. Waiting 1 second before continuing...");
                    currentTarget = null;
                    timer = 20; // 1 second wait
                    state = State.IDLE; // It will return to AFK or to the next button after the timer
                });
            }
            case AFK_A -> {
                if (antiAfkEnabled.get()) {
                    if (walkTo(Vec3d.ofCenter(posA.get()), 2)) {
                        state = State.AFK_B;
                        timer = 20;
                    }
                } else {
                    state = State.IDLE;
                }
            }
            case AFK_B -> {
                if (antiAfkEnabled.get()) {
                    if (walkTo(Vec3d.ofCenter(posB.get()), 2)) {
                        state = State.AFK_A;
                        timer = 20;
                    }
                } else {
                    state = State.IDLE;
                }
            }
            case BUSY -> {
            }
        }
    }

    private boolean walkTo(Vec3d target, int radius) {
        if (useBaritone.get()) {
            BlockPos pos = new BlockPos((int) target.x, (int) target.y, (int) target.z);
            if (!pos.equals(lastBaritoneGoal)) {
                try {
                    Class<?> baritoneAPI = Class.forName("baritone.api.BaritoneAPI");
                    Object provider = baritoneAPI.getMethod("getProvider").invoke(null);
                    Object primary = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
                    Object customGoalProcess = primary.getClass().getMethod("getCustomGoalProcess").invoke(primary);
                    Class<?> goalNear = Class.forName("baritone.api.pathing.goals.GoalNear");
                    Object goal = goalNear.getConstructor(BlockPos.class, int.class).newInstance(pos, radius);
                    Method setGoalAndPath = customGoalProcess.getClass().getMethod("setGoalAndPath", Class.forName("baritone.api.pathing.goals.Goal"));
                    setGoalAndPath.invoke(customGoalProcess, goal);
                    lastBaritoneGoal = pos;
                } catch (Exception e) { return manualWalk(target); }
            }
            return Math.sqrt(mc.player.squaredDistanceTo(target.x, mc.player.getY(), target.z)) < (radius + 0.5);
        }
        return manualWalk(target);
    }

    private boolean manualWalk(Vec3d target) {
        double dist = Math.sqrt(mc.player.squaredDistanceTo(target.x, mc.player.getY(), target.z));
        if (dist < 0.8) { stop(); return true; }
        double dx = target.x - mc.player.getX(), dz = target.z - mc.player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
        mc.player.setYaw(yaw);
        Rotations.rotate(yaw, mc.player.getPitch());
        mc.options.forwardKey.setPressed(true);
        if (mc.player.horizontalCollision && mc.player.isOnGround()) mc.player.jump();
        return false;
    }

    private void stop() {
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        stopBaritone();
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
        Vec3d center = Vec3d.ofCenter(pos);
        Rotations.rotate(Rotations.getYaw(center), Rotations.getPitch(center), 5, () -> {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(center, Direction.UP, pos, false));
            mc.player.swingHand(Hand.MAIN_HAND);
            callback.run();
        });
    }

    private BlockPos parsePos(String xStr, String yStr, String zStr) {
        try { return new BlockPos(Integer.parseInt(xStr), Integer.parseInt(yStr), Integer.parseInt(zStr)); }
        catch (Exception e) { return null; }
    }
}