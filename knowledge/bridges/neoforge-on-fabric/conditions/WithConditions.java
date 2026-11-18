package ${package}.conditions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** NeoForge's net.neoforged.neoforge.common.conditions.WithConditions: a value with the conditions it is loaded under. */
public record WithConditions<A>(List<ICondition> conditions, A carrier) {

    public WithConditions(A carrier, ICondition... conditions) {
        this(List.of(conditions), carrier);
    }

    public WithConditions(A carrier) {
        this(List.of(), carrier);
    }

    public static <A> Builder<A> builder(A carrier) {
        return new Builder<A>().withCarrier(carrier);
    }

    public static class Builder<T> {

        private final List<ICondition> conditions = new ArrayList<>();
        private T carrier;

        public Builder<T> addCondition(ICondition... condition) {
            this.conditions.addAll(List.of(condition));
            return this;
        }

        public Builder<T> addCondition(Collection<ICondition> condition) {
            this.conditions.addAll(condition);
            return this;
        }

        public Builder<T> withCarrier(T carrier) {
            this.carrier = carrier;
            return this;
        }

        public WithConditions<T> build() {
            if (this.carrier == null) throw new IllegalStateException("WithConditions needs a carrier");
            return new WithConditions<>(List.copyOf(this.conditions), this.carrier);
        }
    }
}
