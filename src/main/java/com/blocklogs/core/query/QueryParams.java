package com.blocklogs.core.query;

import com.blocklogs.api.action.ActionCategory;
import com.blocklogs.api.action.ActionType;
import com.blocklogs.api.model.WorldPos;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable, filter set shared by lookups, tree queries, rollbacks and restores.
 *
 * <p>All filters are optional and AND-combined. {@code null}/empty means "no constraint". Build with
 * {@link #builder()}.
 *
 * @param actorUuids    restrict to these player UUIDs
 * @param actorNames    restrict to these actor names (players or {@code #tnt}-style causes)
 * @param actions       restrict to these exact action types
 * @param categories    restrict to these action categories
 * @param world         restrict to this world name
 * @param center        center for a radius search (requires {@code radius})
 * @param radius        block radius around {@code center}
 * @param since         only entries at/after this instant
 * @param until         only entries at/before this instant
 * @param materials     restrict to these namespaced block/item keys
 * @param excludeRolledBack skip entries already rolled back
 * @param limit         max rows for a flat page
 * @param offset        row offset for paging
 */
public record QueryParams(
        Set<UUID> actorUuids,
        Set<String> actorNames,
        Set<ActionType> actions,
        Set<ActionCategory> categories,
        String world,
        WorldPos center,
        Integer radius,
        Instant since,
        Instant until,
        Set<String> materials,
        boolean excludeRolledBack,
        int limit,
        int offset
) {
    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder; produces an immutable {@link QueryParams}. */
    public static final class Builder {
        private Set<UUID> actorUuids = Set.of();
        private Set<String> actorNames = Set.of();
        private Set<ActionType> actions = EnumSet.noneOf(ActionType.class);
        private Set<ActionCategory> categories = EnumSet.noneOf(ActionCategory.class);
        private String world;
        private WorldPos center;
        private Integer radius;
        private Instant since;
        private Instant until;
        private Set<String> materials = Set.of();
        private boolean excludeRolledBack = false;
        private int limit = 100;
        private int offset = 0;

        public Builder actorUuids(Set<UUID> v) { this.actorUuids = v; return this; }
        public Builder actorNames(Set<String> v) { this.actorNames = v; return this; }
        public Builder actions(Set<ActionType> v) { this.actions = v; return this; }
        public Builder categories(Set<ActionCategory> v) { this.categories = v; return this; }
        public Builder world(String v) { this.world = v; return this; }
        public Builder center(WorldPos v) { this.center = v; return this; }
        public Builder radius(Integer v) { this.radius = v; return this; }
        public Builder since(Instant v) { this.since = v; return this; }
        public Builder until(Instant v) { this.until = v; return this; }
        public Builder materials(Set<String> v) { this.materials = v; return this; }
        public Builder excludeRolledBack(boolean v) { this.excludeRolledBack = v; return this; }
        public Builder limit(int v) { this.limit = v; return this; }
        public Builder offset(int v) { this.offset = v; return this; }

        public QueryParams build() {
            return new QueryParams(
                    Collections.unmodifiableSet(actorUuids),
                    Collections.unmodifiableSet(actorNames),
                    Collections.unmodifiableSet(actions),
                    Collections.unmodifiableSet(categories),
                    world, center, radius, since, until,
                    Collections.unmodifiableSet(materials),
                    excludeRolledBack, limit, offset
            );
        }
    }
}
