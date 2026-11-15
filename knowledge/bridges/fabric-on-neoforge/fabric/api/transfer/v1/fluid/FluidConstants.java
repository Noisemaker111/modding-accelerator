package ${package}.fabric.api.transfer.v1.fluid;

/**
 * Fabric API's net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants: Fabric's fluid amounts in droplets, 81000 to the
 * bucket, kept as Fabric's (the converted code still counts droplets; the bridge converts at 81 droplets per NeoForge
 * millibucket where a storage meets a NeoForge handler).
 */
public final class FluidConstants {

    public static final long BUCKET = 81000;
    public static final long BOTTLE = 27000;
    public static final long BOWL = 27000;
    public static final long BLOCK = 81000;
    public static final long INGOT = 9000;
    public static final long NUGGET = 1000;
    public static final long DROPLET = 1;

    public static final int WATER_TEMPERATURE = 300;
    public static final int LAVA_TEMPERATURE = 1300;
    public static final int WATER_VISCOSITY = 1000;
    public static final int LAVA_VISCOSITY = 6000;
    public static final int LAVA_VISCOSITY_NETHER = 2000;
    public static final int VISCOSITY_RATIO = 200;

    private FluidConstants() {}

    /** numerator / denominator of a bucket, in droplets; the fraction must be a whole number of droplets. */
    public static long fromBucketFraction(long numerator, long denominator) {
        long product = numerator * BUCKET;
        if (product % denominator != 0) throw new IllegalArgumentException("Not a whole number of droplets: " + numerator + "/" + denominator + " of a bucket");
        return product / denominator;
    }
}
