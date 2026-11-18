package ${package}.fluids.capability;

import ${package}.fluids.FluidStack;

/**
 * NeoForge 21.1's net.neoforged.neoforge.fluids.capability.IFluidHandler: tanks of fluid in millibuckets, filled and drained
 * at once unless the action is SIMULATE. Capabilities.FluidHandler.BLOCK presents one to Fabric API as a fluid Storage
 * (FluidStorages, 81 droplets to the millibucket) and a Fabric Storage as one.
 */
public interface IFluidHandler {

    enum FluidAction {
        EXECUTE,
        SIMULATE;

        public boolean execute() {
            return this == EXECUTE;
        }

        public boolean simulate() {
            return this == SIMULATE;
        }
    }

    int getTanks();

    FluidStack getFluidInTank(int tank);

    int getTankCapacity(int tank);

    boolean isFluidValid(int tank, FluidStack stack);

    int fill(FluidStack resource, FluidAction action);

    FluidStack drain(FluidStack resource, FluidAction action);

    FluidStack drain(int maxDrain, FluidAction action);
}
