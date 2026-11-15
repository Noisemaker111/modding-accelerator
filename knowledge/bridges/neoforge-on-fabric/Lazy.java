package ${package};

import java.util.function.Supplier;

/** NeoForge's net.neoforged.neoforge.common.util.Lazy: a supplier that computes its value once, until invalidated. */
public final class Lazy<T> implements Supplier<T> {

    private Supplier<T> supplier;
    private T value;
    private boolean done;

    private Lazy(Supplier<T> supplier) {
        this.supplier = supplier;
    }

    public static <T> Lazy<T> of(Supplier<T> supplier) {
        return new Lazy<>(supplier);
    }

    public synchronized void invalidate() {
        done = false;
        value = null;
    }

    @Override
    public synchronized T get() {
        if (!done) {
            value = supplier.get();
            done = true;
        }
        return value;
    }
}
