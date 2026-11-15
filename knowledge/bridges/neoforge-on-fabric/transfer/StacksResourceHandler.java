package ${package}.transfer;

import ${package}.common.util.ValueIOSerializable;
import ${package}.transfer.resource.Resource;
import ${package}.transfer.transaction.SnapshotJournal;
import ${package}.transfer.transaction.TransactionContext;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.core.NonNullList;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * NeoForge's net.neoforged.neoforge.transfer.StacksResourceHandler: a handler over a list of stacks, one per index, saved
 * under "stacks". Each index has its own journal, so an aborted transaction restores the stacks it changed and
 * onContentsChanged(index, previous) runs once per changed index after the root transaction commits.
 */
public abstract class StacksResourceHandler<S, T extends Resource> implements ResourceHandler<T>, ValueIOSerializable {

    public static final String VALUE_IO_KEY = "stacks";

    protected final S emptyStack;
    protected NonNullList<S> stacks;
    protected final Codec<NonNullList<S>> codec;
    private final List<StackJournal> journals = new ArrayList<>();

    protected StacksResourceHandler(int size, S emptyStack, Codec<S> stackCodec) {
        this(NonNullList.withSize(size, emptyStack), emptyStack, stackCodec);
    }

    protected StacksResourceHandler(NonNullList<S> stacks, S emptyStack, Codec<S> stackCodec) {
        this.emptyStack = emptyStack;
        this.stacks = mutableCopyOf(stacks);
        this.codec = stackCodec.listOf().xmap(this::mutableCopyOf, Function.identity());
        updateJournals();
    }

    @SuppressWarnings("unchecked")
    private NonNullList<S> mutableCopyOf(Collection<S> list) {
        return NonNullList.of(emptyStack, (S[]) list.toArray(Object[]::new));
    }

    protected void setStacks(NonNullList<S> stacks) {
        this.stacks = mutableCopyOf(stacks);
        updateJournals();
    }

    private void updateJournals() {
        while (journals.size() < stacks.size()) journals.add(new StackJournal(journals.size()));
        if (journals.size() > stacks.size()) journals.subList(stacks.size(), journals.size()).clear();
    }

    @Override
    public void serialize(ValueOutput output) {
        output.store(VALUE_IO_KEY, codec, stacks);
    }

    @Override
    public void deserialize(ValueInput input) {
        input.read(VALUE_IO_KEY, codec).ifPresent(list -> {
            stacks = list;
            updateJournals();
        });
    }

    /** Sets an index's contents outside a transaction. */
    public void set(int index, T resource, int amount) {
        TransferBridge.checkNonNegative(amount);
        if (resource.isEmpty() && amount > 0) throw new IllegalArgumentException("Resource is empty but the amount is positive: " + amount);
        S previous = stacks.set(index, getStackFrom(resource, amount));
        onContentsChanged(index, previous);
    }

    protected abstract T getResourceFrom(S stack);

    protected abstract int getAmountFrom(S stack);

    protected abstract S getStackFrom(T resource, int amount);

    protected abstract S copyOf(S stack);

    protected boolean matches(S stack, T resource) {
        return getResourceFrom(stack).equals(resource);
    }

    @Override
    public boolean isValid(int index, T resource) {
        return true;
    }

    protected abstract int getCapacity(int index, T resource);

    protected void onContentsChanged(int index, S previousContents) {}

    public NonNullList<S> copyToList() {
        return mutableCopyOf(stacks);
    }

    @Override
    public int size() {
        return stacks.size();
    }

    @Override
    public T getResource(int index) {
        Objects.checkIndex(index, size());
        return getResourceFrom(stacks.get(index));
    }

    @Override
    public long getAmountAsLong(int index) {
        Objects.checkIndex(index, size());
        return getAmountFrom(stacks.get(index));
    }

    @Override
    public long getCapacityAsLong(int index, T resource) {
        Objects.checkIndex(index, size());
        return resource.isEmpty() || isValid(index, resource) ? getCapacity(index, resource) : 0;
    }

    @Override
    public int insert(int index, T resource, int amount, TransactionContext transaction) {
        Objects.checkIndex(index, size());
        TransferBridge.checkNonEmptyNonNegative(resource, amount);
        S current = stacks.get(index);
        int currentAmount = getAmountFrom(current);
        if (currentAmount != 0 && !matches(current, resource) || !isValid(index, resource)) return 0;
        int inserted = Math.min(amount, getCapacity(index, resource) - currentAmount);
        if (inserted <= 0) return 0;
        journals.get(index).updateSnapshots(transaction);
        stacks.set(index, getStackFrom(resource, currentAmount + inserted));
        return inserted;
    }

    @Override
    public int extract(int index, T resource, int amount, TransactionContext transaction) {
        Objects.checkIndex(index, size());
        TransferBridge.checkNonEmptyNonNegative(resource, amount);
        S current = stacks.get(index);
        if (!matches(current, resource)) return 0;
        int currentAmount = getAmountFrom(current);
        int extracted = Math.min(amount, currentAmount);
        if (extracted <= 0) return 0;
        journals.get(index).updateSnapshots(transaction);
        stacks.set(index, getStackFrom(resource, currentAmount - extracted));
        return extracted;
    }

    private final class StackJournal extends SnapshotJournal<S> {

        private final int index;

        private StackJournal(int index) {
            this.index = index;
        }

        @Override
        protected S createSnapshot() {
            return copyOf(stacks.get(index));
        }

        @Override
        protected void revertToSnapshot(S snapshot) {
            stacks.set(index, snapshot);
        }

        @Override
        protected void onRootCommit(S originalState) {
            onContentsChanged(index, originalState);
        }
    }
}
