package dev.autocart;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/** Finds and validates a rail position (and the fire position behind it) in front of the player. */
final class RailPlacement {
    private RailPlacement() {}

    record Spot(BlockPos support, BlockPos rail, Direction facing) {
        Vec3 railCenter() { return new Vec3(rail.getX() + 0.5, rail.getY(), rail.getZ() + 0.5); }

        /** Cell where the fire goes: directly behind the rail (towards the player). */
        BlockPos firePos() { return rail.relative(facing.getOpposite()); }

        /** Click point on the top face of the supporting block (where the rail gets placed). */
        BlockHitResult placeHit() {
            return new BlockHitResult(new Vec3(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5),
                    Direction.UP, support, false);
        }

        /** Click point on the rail itself (used to place the minecart). */
        BlockHitResult cartHit() {
            return new BlockHitResult(new Vec3(rail.getX() + 0.5, rail.getY() + 0.125, rail.getZ() + 0.5),
                    Direction.UP, rail, false);
        }
    }

    /** Finds a rail spot where BOTH the rail and the fire behind it are valid. Places nothing. */
    static Optional<Spot> find(Minecraft mc, AutoCartConfig cfg) {
        LocalPlayer p = mc.player;
        Direction facing = p.getDirection();
        BlockPos base = p.blockPosition();
        int[] dys = {0, -1, 1};

        for (int dist = cfg.railDistanceMin; dist <= cfg.railDistanceMax; dist++) {
            for (int dy : dys) {
                BlockPos rail = base.relative(facing, dist).offset(0, dy, 0);
                Spot spot = new Spot(rail.below(), rail, facing);
                if (railValid(mc, spot) && fireValid(mc, spot)) return Optional.of(spot);
            }
        }
        return Optional.empty();
    }

    /** Rail cell free, supported, in reach, visible and not occupied by a mob/cart. */
    static boolean railValid(Minecraft mc, Spot spot) {
        LocalPlayer p = mc.player;
        BlockState state = mc.level.getBlockState(spot.rail());
        if (!state.canBeReplaced() || !state.getFluidState().isEmpty()) return false;
        if (!mc.level.getBlockState(spot.support()).isFaceSturdy(mc.level, spot.support(), Direction.UP)) return false;

        Vec3 eye = p.getEyePosition();
        Vec3 hit = spot.placeHit().getLocation();
        if (eye.distanceTo(hit) > McCompat.blockRange(p) - 0.3) return false;
        if (!clearView(mc, eye, hit)) return false;

        // Nothing standing in the rail cell (a mob/cart would block the minecart).
        return mc.level.getEntities((Entity) null, new AABB(spot.rail()),
                e -> e != p && (e instanceof LivingEntity || e.getType() == EntityType.TNT_MINECART)).isEmpty();
    }

    /** Fire cell free, on a solid floor, in reach for flint and steel, visible, and not where the player stands. */
    static boolean fireValid(Minecraft mc, Spot spot) {
        LocalPlayer p = mc.player;
        BlockPos firePos = spot.firePos();
        BlockPos floor = firePos.below();

        BlockState state = mc.level.getBlockState(firePos);
        if (!state.canBeReplaced() || !state.getFluidState().isEmpty()) return false;
        if (!mc.level.getBlockState(floor).isFaceSturdy(mc.level, floor, Direction.UP)) return false;

        // Do not light a fire inside the player.
        if (p.getBoundingBox().intersects(new AABB(firePos))) return false;

        Vec3 eye = p.getEyePosition();
        Vec3 hit = new Vec3(floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5);
        if (eye.distanceTo(hit) > McCompat.blockRange(p) - 0.3) return false;
        return clearView(mc, eye, hit);
    }

    private static boolean clearView(Minecraft mc, Vec3 eye, Vec3 hit) {
        BlockHitResult clip = mc.level.clip(new ClipContext(eye, hit.add(0, 0.001, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return clip.getType() == HitResult.Type.MISS;
    }
}
