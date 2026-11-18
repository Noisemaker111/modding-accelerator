package ${package}.datamaps;

import ${package}.Event;
import ${package}.IModBusEvent;

/**
 * NeoForge's net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent, posted on the mod bus when the mod's
 * main entrypoint ends (Bridge.common): a type registered here is loaded with the data packs (DataMaps). A type
 * registered twice is an error.
 */
public class RegisterDataMapTypesEvent extends Event implements IModBusEvent {

    public RegisterDataMapTypesEvent() {}

    public <T, R> void register(DataMapType<R, T> type) {
        DataMaps.register(type);
    }
}
