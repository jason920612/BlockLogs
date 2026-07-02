package com.blocklogs.core.storage.sqlite;

import com.blocklogs.api.action.ActionType;
import com.blocklogs.api.model.WorldPos;
import com.blocklogs.core.query.QueryParams;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compact, self-contained text codec for persisting {@link QueryParams} and {@code long} lists in the
 * {@code bl_session} table.
 *
 * <p>Rather than pulling in a JSON dependency, {@link QueryParams} is flattened to a small newline-free,
 * key/value string using {@code ';'} between fields and {@code '='} between key and value, with list
 * values comma-joined. A tiny escaping scheme (backslash-escapes for the structural characters
 * {@code ; = , \}) keeps arbitrary world/actor/material strings safe. This is intentionally minimal and
 * fully round-trips the fields the query UI cares about.
 *
 * <p><b>Covered fields:</b> actorNames, actorUuids, actions (by numeric id), world, center+radius,
 * since/until (epoch millis), materials, excludeRolledBack, limit, offset.
 *
 * <p>TODO(storage): {@link QueryParams#categories()} is intentionally not persisted separately — when a
 * session is saved, categories are already expanded to concrete action ids at query time by the storage
 * WHERE builder, so persisting the resolved {@code actions} set is sufficient for reproducing the view.
 * If a future UI needs to distinguish "category filter" from "explicit action filter", add a
 * {@code categories} key here.
 */
final class SessionCodec {

    private SessionCodec() {
    }

    // ------------------------------------------------------------------ long lists

    static String encodeLongs(Iterable<Long> values) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Long v : values) {
            if (!first) {
                sb.append(',');
            }
            sb.append(v);
            first = false;
        }
        return sb.toString();
    }

    static List<Long> decodeLongs(String encoded) {
        List<Long> out = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return out;
        }
        for (String part : encoded.split(",")) {
            if (!part.isEmpty()) {
                out.add(Long.parseLong(part.trim()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ QueryParams

    static String encodeParams(QueryParams p) {
        Map<String, String> fields = new LinkedHashMap<>();

        if (!p.actorUuids().isEmpty()) {
            fields.put("actorUuids", joinStrings(p.actorUuids().stream().map(Object::toString).toList()));
        }
        if (!p.actorNames().isEmpty()) {
            fields.put("actorNames", joinStrings(p.actorNames()));
        }
        if (!p.actions().isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (ActionType a : p.actions()) {
                ids.add(Integer.toString(a.id()));
            }
            fields.put("actions", joinStrings(ids));
        }
        if (p.world() != null) {
            fields.put("world", escape(p.world()));
        }
        if (p.center() != null) {
            WorldPos c = p.center();
            fields.put("center", escape(c.world()) + "," + c.x() + "," + c.y() + "," + c.z());
        }
        if (p.radius() != null) {
            fields.put("radius", Integer.toString(p.radius()));
        }
        if (p.since() != null) {
            fields.put("since", Long.toString(p.since().toEpochMilli()));
        }
        if (p.until() != null) {
            fields.put("until", Long.toString(p.until().toEpochMilli()));
        }
        if (!p.materials().isEmpty()) {
            fields.put("materials", joinStrings(p.materials()));
        }
        if (p.excludeRolledBack()) {
            fields.put("excludeRolledBack", "1");
        }
        fields.put("limit", Integer.toString(p.limit()));
        fields.put("offset", Integer.toString(p.offset()));

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!first) {
                sb.append(';');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    static QueryParams decodeParams(String encoded) {
        QueryParams.Builder b = QueryParams.builder();
        if (encoded == null || encoded.isEmpty()) {
            return b.build();
        }

        for (String field : splitTop(encoded, ';')) {
            int eq = field.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = field.substring(0, eq);
            String value = field.substring(eq + 1);
            switch (key) {
                case "actorUuids" -> {
                    Set<java.util.UUID> uuids = new LinkedHashSet<>();
                    for (String s : splitStrings(value)) {
                        uuids.add(java.util.UUID.fromString(s));
                    }
                    b.actorUuids(uuids);
                }
                case "actorNames" -> b.actorNames(new LinkedHashSet<>(splitStrings(value)));
                case "actions" -> {
                    Set<ActionType> actions = EnumSet.noneOf(ActionType.class);
                    for (String s : splitStrings(value)) {
                        actions.add(ActionType.byId(Integer.parseInt(s)));
                    }
                    b.actions(actions);
                }
                case "world" -> b.world(unescape(value));
                case "center" -> {
                    // center is "world,x,y,z" with world escaped; split on top-level commas.
                    List<String> parts = splitTop(value, ',');
                    if (parts.size() == 4) {
                        b.center(new WorldPos(
                                unescape(parts.get(0)),
                                Integer.parseInt(parts.get(1)),
                                Integer.parseInt(parts.get(2)),
                                Integer.parseInt(parts.get(3))));
                    }
                }
                case "radius" -> b.radius(Integer.parseInt(value));
                case "since" -> b.since(Instant.ofEpochMilli(Long.parseLong(value)));
                case "until" -> b.until(Instant.ofEpochMilli(Long.parseLong(value)));
                case "materials" -> b.materials(new LinkedHashSet<>(splitStrings(value)));
                case "excludeRolledBack" -> b.excludeRolledBack("1".equals(value));
                case "limit" -> b.limit(Integer.parseInt(value));
                case "offset" -> b.offset(Integer.parseInt(value));
                default -> {
                    // unknown/future key — ignore for forward compatibility.
                }
            }
        }
        return b.build();
    }

    // ------------------------------------------------------------------ escaping helpers

    private static String joinStrings(Iterable<String> values) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String v : values) {
            if (!first) {
                sb.append(',');
            }
            sb.append(escape(v));
            first = false;
        }
        return sb.toString();
    }

    private static List<String> splitStrings(String value) {
        List<String> out = new ArrayList<>();
        for (String part : splitTop(value, ',')) {
            out.add(unescape(part));
        }
        return out;
    }

    /** Escapes the structural characters {@code \ ; = ,} with a leading backslash. */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == ';' || c == '=' || c == ',') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                sb.append(c);
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Splits on {@code delim} at the top level, honoring backslash escapes (escaped delims stay). */
    private static List<String> splitTop(String s, char delim) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                cur.append('\\').append(c);
                esc = false;
            } else if (c == '\\') {
                cur.append('\\');
                esc = true;
            } else if (c == delim) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (esc) {
            cur.append('\\');
        }
        out.add(cur.toString());
        return out;
    }
}
