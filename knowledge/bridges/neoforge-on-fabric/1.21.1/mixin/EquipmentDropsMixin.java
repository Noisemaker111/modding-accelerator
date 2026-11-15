package ${package}.mixin;

/**
 * Not a mixin on Minecraft 1.21.1, and not listed in its mixin config: a mob's equipment drops there through
 * Entity.spawnAtLocation, which EntityDropsMixin captures. This file only takes the place of the 26.x
 * EquipmentDropsMixin, whose EntityEquipment class 1.21.1 does not have.
 */
final class EquipmentDropsMixin {

    private EquipmentDropsMixin() {}
}
