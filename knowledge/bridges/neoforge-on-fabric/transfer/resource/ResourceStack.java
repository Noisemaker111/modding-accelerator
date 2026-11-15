package ${package}.transfer.resource;

/** NeoForge's net.neoforged.neoforge.transfer.resource.ResourceStack: a resource and an amount of it. */
public record ResourceStack<T extends Resource>(T resource, int amount) {

    public boolean isEmpty() {
        return resource.isEmpty() || amount <= 0;
    }

    @Override
    public String toString() {
        return amount + "x " + resource;
    }
}
