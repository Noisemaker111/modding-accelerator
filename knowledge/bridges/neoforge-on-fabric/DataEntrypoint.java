package ${package};

import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
import ${package}.data.GatherDataEvent;

/**
 * The fabric-datagen entrypoint mmt convert adds to a NeoForge mod that generates data: Fabric's data run posts NeoForge's
 * GatherDataEvent.Client, then .Server, on the mod bus, with Fabric's generator and registries. The mod's main entrypoint
 * has run by then (Fabric runs mod initializers before data generation), so its GatherDataEvent listeners are on the bus.
 */
public final class DataEntrypoint implements DataGeneratorEntrypoint {

    @Override
    public void onInitializeDataGenerator(FabricDataGenerator generator) {
        GatherDataEvent.DataGeneratorConfig config = new GatherDataEvent.DataGeneratorConfig(
            Set.of(generator.getModId()),
            List.of(),
            generator.getRegistries(),
            generator.isStrictValidationEnabled()
        );
        ModContainer container = Bridge.container(generator.getModId());
        Bus.INSTANCE.post(new GatherDataEvent.Client(container, generator, config));
        Bus.INSTANCE.post(new GatherDataEvent.Server(container, generator, config));
    }
}
