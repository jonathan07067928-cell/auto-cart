package dev.autocart;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Runs the whole TNT minecart sequence as a state machine.
 * Stages that do not need to wait run back-to-back inside a single client tick.
 */
final class AutoCartController {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoCart");
    private static final boolean SHOW_STATUS = false; // false = no ON/OFF/status text at all

    enum Stage {
        IDLE("Idle"),
        PREPARE("Preparing items"),
        PREPARE_VERIFY("Verifying inventory"),
        PLACE_RAIL("Placing rail"),
        VERIFY_RAIL("Verifying rail"),
        PLACE_CART("Placing TNT minecart"),
        VERIFY_CART("Verifying minecart"),
        MOVE_BEHIND("Moving behind rail"),
        IGNITE("Lighting fire behind rail"),
        VERIFY_IGNITE("Verifying ignition"),
        FIRE("Firing crossbow"),
        VERIFY_FIRE("Verifying shot");

        final String label;
        Stage(String label) { this.label = label; }
    }

    private static final Predicate<ItemStack> RAIL = s -> s.is(Items.RAIL);
    private static final Predicate<ItemStack> CART = s -> s.is(Items.TNT_MINECART);
    private static final Predicate<ItemStack> FLINT = s -> s.is(Items.FLINT_AND_STEEL);
    private static final Predicate<ItemStack> CROSSBOW = s -> s.is(Items.CROSSBOW) && McCompat.isCharged(s);

    private final AutoCartConfig cfg;
    private final InventoryManager inv = new InventoryManager();

    private boolean enabled;
    private Stage stage = Stage.IDLE;
    private int wait;
    private int stageTicks;
    private int overlayTimer;
    private int holdTicks;
    private String finalMsg;

    private int railSlot, cartSlot, flintSlot, crossbowSlot;
    private RailPlacement.Spot spot;
    private Entity cart;
    private Vec3 lastPos;
    private int stuckTicks;

    AutoCartController(AutoCartConfig cfg) { this.cfg = cfg; }

    // ------------------------------------------------------------------ control

    void toggle(Minecraft mc) {
        if (enabled) {
            end(mc, "Cancelled");
            return;
        }
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) return;
        if (McCompat.screenOpen(mc)) return;
        finalMsg = null;
        inv.begin(p);
        enabled = true;
        go(Stage.PREPARE, 0);
        show(mc);
    }

    void tick(Minecraft mc) {
        if (!enabled) {
            if (holdTicks > 0 && --holdTicks % 10 == 0 && mc.player != null) show(mc);
            return;
        }
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) {
            enabled = false;
            stage = Stage.IDLE;
            return;
        }
        if (p.isDeadOrDying()) { fail(mc, "player died"); return; }

        if (++overlayTimer >= 5) { overlayTimer = 0; show(mc); }

        stageTicks++;
        if (stageTicks > cfg.stageTimeoutTicks + McCompat.pingTicks(mc) * 2) {
            fail(mc, "timed out during: " + stage.label);
            return;
        }
        if (wait > 0) { wait--; return; }
        if (McCompat.screenOpen(mc)) return;

        // Burst: keep running stages in this same tick until a stage has to wait.
        for (int guard = 0; guard < 16 && enabled && wait == 0; guard++) {
            Stage before = stage;
            switch (stage) {
                case PREPARE -> prepare(mc);
                case PREPARE_VERIFY -> prepareVerify(mc);
                case PLACE_RAIL -> placeRail(mc);
                case PLACE_CART -> placeCart(mc);
                case MOVE_BEHIND -> moveBehind(mc);
                case IGNITE -> ignite(mc);
                case FIRE -> fire(mc);
                case VERIFY_FIRE -> verifyFire(mc);
                default -> { }
            }
            if (stage == before) break; // stage is polling/walking, continue next tick
        }
    }

    // ------------------------------------------------------------------ stages

    private void prepare(Minecraft mc) {
        Set<Integer> reserved = new HashSet<>();
        if ((railSlot = take(mc, RAIL, reserved, "Rail")) < 0) return;
        if ((cartSlot = take(mc, CART, reserved, "TNT Minecart")) < 0) return;
        if ((flintSlot = take(mc, FLINT, reserved, "Flint and Steel")) < 0) return;
        if ((crossbowSlot = take(mc, CROSSBOW, reserved, "charged Crossbow")) < 0) return;
        go(Stage.PREPARE_VERIFY, 0);
    }

    /** @return hotbar slot, or -1 after failing the sequence (item missing / no free slot). */
    private int take(Minecraft mc, Predicate<ItemStack> match, Set<Integer> reserved, String name) {
        int slot = inv.ensureInHotbar(mc, match, reserved);
        if (slot < 0) {
            fail(mc, "missing item: " + name);
            return -1;
        }
        reserved.add(slot);
        return slot;
    }

    private void prepareVerify(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!RAIL.test(p.getInventory().getItem(railSlot))
                || !CART.test(p.getInventory().getItem(cartSlot))
                || !FLINT.test(p.getInventory().getItem(flintSlot))
                || !CROSSBOW.test(p.getInventory().getItem(crossbowSlot))) {
            fail(mc, "inventory swap was not accepted by the server");
            return;
        }
        go(Stage.PLACE_RAIL, 0);
    }

    private void placeRail(Minecraft mc) {
        if (!hold(mc, railSlot, RAIL)) { fail(mc, "could not select rail"); return; }
        Optional<RailPlacement.Spot> found = RailPlacement.find(mc, cfg);
        if (found.isEmpty()) { fail(mc, "no valid rail position in front of you"); return; }
        spot = found.get();
        if (!McCompat.useItemOn(mc, spot.placeHit())) { fail(mc, "rail placement refused"); return; }
        go(Stage.PLACE_CART, 0);
    }

    private void placeCart(Minecraft mc) {
        if (!mc.level.getBlockState(spot.rail()).is(BlockTags.RAILS)) { fail(mc, "rail disappeared"); return; }
        if (!hold(mc, cartSlot, CART)) { fail(mc, "could not select TNT minecart"); return; }
        if (!McCompat.useItemOn(mc, spot.cartHit())) { fail(mc, "minecart placement refused"); return; }
        lastPos = null;
        stuckTicks = 0;
        go(Stage.MOVE_BEHIND, 0);
    }

    private void moveBehind(Minecraft mc) {
        LocalPlayer p = mc.player;
        Vec3 railC = spot.railCenter();
        double fx = spot.facing().getStepX(), fz = spot.facing().getStepZ();

        // axial > 0 means "behind" the rail; must also be clear of the fire cell.
        double vx = p.getX() - railC.x, vz = p.getZ() - railC.z;
        double axial = -(vx * fx + vz * fz);
        double lateral = Math.abs(vx * fz - vz * fx);
        if (axial >= 1.9 && axial <= 3.5 && lateral <= 0.6) {
            go(Stage.IGNITE, 0);
            return;
        }

        Vec3 target = new Vec3(railC.x - fx * cfg.standDistance, p.getY(), railC.z - fz * cfg.standDistance);
        double dx = target.x - p.getX(), dz = target.z - p.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.15) { go(Stage.IGNITE, 0); return; }

        double s = Math.min(cfg.walkSpeed, dist);
        p.setDeltaMovement(dx / dist * s, p.getDeltaMovement().y, dz / dist * s);

        Vec3 now = p.position();
        if (lastPos != null && now.distanceToSqr(lastPos) < 0.0004) stuckTicks++; else stuckTicks = 0;
        lastPos = now;
        if (stuckTicks > 10) fail(mc, "cannot reach a spot behind the rail (blocked)");
    }

    /** Flint and steel on the floor block behind the rail: fire appears in the cell directly behind the rail. */
    private void ignite(Minecraft mc) {
        if (!hold(mc, flintSlot, FLINT)) { fail(mc, "could not select flint and steel"); return; }

        BlockPos firePos = spot.rail().relative(spot.facing().getOpposite());
        BlockPos floor = firePos.below();
        BlockState at = mc.level.getBlockState(firePos);
        if (!at.canBeReplaced() || !at.getFluidState().isEmpty()) { fail(mc, "cell behind the rail is not free for fire"); return; }
        if (!mc.level.getBlockState(floor).isFaceSturdy(mc.level, floor, Direction.UP)) { fail(mc, "no solid floor behind the rail"); return; }

        Vec3 hitLoc = new Vec3(floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5);
        if (mc.player.getEyePosition().distanceTo(hitLoc) > McCompat.blockRange(mc.player) - 0.3) {
            fail(mc, "fire position out of reach");
            return;
        }
        if (!McCompat.useItemOn(mc, new BlockHitResult(hitLoc, Direction.UP, floor, false))) {
            fail(mc, "flint and steel use refused");
            return;
        }
        go(Stage.FIRE, 0);
    }

    /** Switch to the crossbow, aim at the TNT minecart and fire so the shot passes through the fire cell. */
    private void fire(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!hold(mc, crossbowSlot, CROSSBOW)) { fail(mc, "charged crossbow not selectable"); return; }

        BlockPos firePos = spot.rail().relative(spot.facing().getOpposite());
        Vec3 eye = p.getEyePosition();

        // Target = real cart hitbox centre if the server already spawned it, otherwise where it spawns (on the rail).
        Vec3 target = null;
        for (Entity e : cartsNear(mc)) { target = e.getBoundingBox().getCenter(); break; }
        if (target == null) {
            Vec3 rc = spot.railCenter();
            target = new Vec3(rc.x, rc.y + 0.35, rc.z);
        }

        // The shot must cross the fire cell before it reaches the cart.
        if (new AABB(firePos).clip(eye, target).isEmpty()) { fail(mc, "shot would not pass through the fire"); return; }
        if (!McCompat.lineOfSight(mc, target)) { fail(mc, "no line of sight for the shot"); return; }

        // Arrow gravity compensation (crossbow arrow ~3.15 blocks/tick, gravity 0.05/tick).
        double t = eye.distanceTo(target) / 3.15;
        Vec3 aim = target.add(0, 0.05 * t * (t + 1.0) / 2.0, 0);

        Vec3 d = aim.subtract(eye);
        double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, horiz));

        // The use-item packet carries yaw/pitch, so the server aims the shot there; the camera is restored in the same tick.
        if (!McCompat.useItemAimed(mc, yaw, pitch)) { fail(mc, "crossbow use refused"); return; }
        go(Stage.VERIFY_FIRE, verifyWait(mc));
    }

    private void verifyFire(Minecraft mc) {
        if (!McCompat.isCharged(mc.player.getInventory().getItem(crossbowSlot))) {
            end(mc, "Sequence complete");
            return;
        }
        if (stageTicks > cfg.verifyTimeoutTicks + McCompat.pingTicks(mc) * 2) {
            fail(mc, "crossbow did not fire");
        }
    }

    // ------------------------------------------------------------------ helpers

    private void go(Stage next, int waitTicks) {
        stage = next;
        wait = Math.max(0, waitTicks);
        stageTicks = 0;
    }

    private int verifyWait(Minecraft mc) { return cfg.verifyDelayTicks + McCompat.pingTicks(mc); }

    /** Select a hotbar slot and confirm the main hand really holds the expected item. */
    private boolean hold(Minecraft mc, int slot, Predicate<ItemStack> match) {
        McCompat.selectSlot(mc.player, slot);
        return match.test(mc.player.getMainHandItem());
    }

    private boolean cartAlive() { return cart != null && cart.isAlive() && !cart.isRemoved(); }

    private List<Entity> cartsNear(Minecraft mc) {
        AABB box = new AABB(spot.rail()).inflate(0.5);
        return mc.level.getEntities((Entity) null, box, e -> e.getType() == EntityType.TNT_MINECART);
    }

    private void fail(Minecraft mc, String reason) {
        LOGGER.warn("Sequence aborted at {}: {}", stage, reason);
        end(mc, "FAILED - " + reason);
    }

    /** Stops the sequence (success, failure or cancel), restores the inventory and switches to OFF. */
    private void end(Minecraft mc, String message) {
        if (cfg.restoreInventory) inv.restore(mc);
        enabled = false;
        stage = Stage.IDLE;
        cart = null;
        spot = null;
        finalMsg = message;
        holdTicks = 60;
        if (mc.player != null) show(mc);
    }

    private void show(Minecraft mc) {
        if (!SHOW_STATUS) return;
        String text = enabled
                ? "Auto Cart: ON - " + stage.label
                : "Auto Cart: OFF" + (finalMsg == null ? "" : " - " + finalMsg);
        McCompat.overlay(mc, Component.literal(text));
    }
}
