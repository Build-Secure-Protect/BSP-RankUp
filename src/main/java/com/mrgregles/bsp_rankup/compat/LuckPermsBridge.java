package com.mrgregles.bsp_rankup.compat;

import com.mrgregles.bsp_rankup.BSPRankUp;
import net.minecraft.Util;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The only class that touches LuckPerms. Reads a player's groups and writes rank changes along a LuckPerms track.
 *
 * <p>Two ways in, as in BSP-Region Guard's bridge. On a Forge server with the LuckPerms Forge mod the API classes
 * are on our class path and {@code LuckPermsProvider.get()} is called directly. On a Mohist server with the
 * LuckPerms Bukkit plugin the plugin lives in its own class loader, so the bridge finds it through Bukkit's plugin
 * manager and loads the API from that loader. After that, <b>every call goes by reflection on both routes</b>,
 * through the same code: the dev runs (Forge route) then exercise exactly the code that runs on the live Mohist
 * backends (Bukkit route), instead of a second copy that no test can reach.
 *
 * <p>Every write returns a {@link CompletableFuture} that completes on a LuckPerms thread. Callers hop back to the
 * server thread ({@code server.execute}) before touching the game. Reads answer at once from LuckPerms's loaded data
 * (online players are always loaded).
 */
public final class LuckPermsBridge {
    private static final String PROVIDER = "net.luckperms.api.LuckPermsProvider";
    private static final String CONTEXT_SET = "net.luckperms.api.context.ImmutableContextSet";
    private static final String INHERITANCE_NODE = "net.luckperms.api.node.types.InheritanceNode";
    private static final String PREFIX_NODE = "net.luckperms.api.node.types.PrefixNode";
    private static final String WEIGHT_NODE = "net.luckperms.api.node.types.WeightNode";
    /** Weight given to the first group {@link #ensureTrack} creates; each later group gets one step more. */
    public static final int WEIGHT_STEP = 10;
    private static final long RETRY_NANOS = 5_000_000_000L;

    /** How LuckPerms was reached. */
    public enum Route { NONE, FORGE, BUKKIT }

    /** What a move along a track did. */
    public enum Outcome {
        /** Moved up one group. */
        PROMOTED,
        /** Was on no group of the track and is now on its first. */
        ADDED_TO_FIRST,
        /** Moved down one group. */
        DEMOTED,
        /** Was on the first group and is now on none of the track. */
        REMOVED_FROM_FIRST,
        /** Already on the last group. */
        END_OF_TRACK,
        /** A demotion for a player on none of the track's groups. */
        NOT_ON_TRACK,
        /** The track does not exist. */
        NO_TRACK,
        /** LuckPerms is not reachable. */
        NO_LUCKPERMS,
        /** LuckPerms refused (a malformed track, a group that does not exist, an ambiguous position) or threw. */
        FAILED;

        public boolean changed() {
            return this == PROMOTED || this == ADDED_TO_FIRST || this == DEMOTED || this == REMOVED_FROM_FIRST;
        }
    }

    /** The result of a move along a track: the outcome and the groups left and reached (null when none). */
    public record RankChange(Outcome outcome, @Nullable String from, @Nullable String to) {
        public boolean changed() {
            return outcome.changed();
        }
    }

    @Nullable
    private static Route route;
    /** The LuckPerms API object (a class from the loader below). */
    @Nullable
    private static Object api;
    /** The class loader the API classes live in: ours on Forge, the plugin's on Bukkit. */
    @Nullable
    private static ClassLoader loader;
    private static long lastMiss;

    private LuckPermsBridge() {
    }

    public static boolean isPresent() {
        return route() != Route.NONE;
    }

    /**
     * The route in use. A found route is cached; a miss is retried every few seconds, since LuckPerms may register
     * its provider after the first question is asked during start-up.
     */
    public static Route route() {
        if (route == null || (route == Route.NONE && System.nanoTime() - lastMiss > RETRY_NANOS)) {
            route = detect();
            if (route != Route.NONE) {
                BSPRankUp.LOGGER.info("LuckPerms reached through the {} edition", route == Route.FORGE ? "Forge" : "Bukkit");
            } else {
                lastMiss = System.nanoTime();
            }
        }
        return route;
    }

    private static Route detect() {
        try {
            Class<?> provider = Class.forName(PROVIDER);
            api = net.luckperms.api.LuckPermsProvider.get();
            loader = provider.getClassLoader();
            return Route.FORGE;
        } catch (ClassNotFoundException | NoClassDefFoundError | IllegalStateException e) {
            // not on our class path, or not registered yet: try Bukkit
        }
        try {
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
            Object pluginManager = bukkit.getMethod("getPluginManager").invoke(null);
            if (pluginManager == null) {
                return Route.NONE;
            }
            Object plugin = pluginManager.getClass().getMethod("getPlugin", String.class).invoke(pluginManager, "LuckPerms");
            if (plugin == null) {
                return Route.NONE;
            }
            ClassLoader pluginLoader = plugin.getClass().getClassLoader();
            Class<?> provider = pluginLoader.loadClass(PROVIDER);
            api = provider.getMethod("get").invoke(null);
            loader = pluginLoader;
            return api == null ? Route.NONE : Route.BUKKIT;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            BSPRankUp.LOGGER.debug("LuckPerms not reachable through Bukkit: {}", e.toString());
            api = null;
            loader = null;
            return Route.NONE;
        }
    }

    /** Forgets the cached answer, for example after a reload. */
    public static void reset() {
        route = null;
        api = null;
        loader = null;
    }

    // ------------------------------------------------------------------ reads

    /**
     * The groups a player is in, directly or by inheritance, lower-cased. Empty when LuckPerms is absent or the
     * user is not loaded (online players always are).
     */
    public static Set<String> groupsOf(UUID player) {
        if (!isPresent()) {
            return Collections.emptySet();
        }
        try {
            Object user = loadedUser(player);
            if (user == null) {
                return Collections.emptySet();
            }
            Set<String> out = new TreeSet<>();
            out.add(((String) call(user, "getPrimaryGroup")).toLowerCase(Locale.ROOT));
            Object queryOptions = call(user, "getQueryOptions");
            Collection<?> groups = (Collection<?>) call(user, "getInheritedGroups", queryOptions);
            for (Object g : groups) {
                out.add(((String) call(g, "getName")).toLowerCase(Locale.ROOT));
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn("user lookup", e);
            return Collections.emptySet();
        }
    }

    /** The player's primary group, lower-cased; empty when LuckPerms is absent or the user is not loaded. */
    public static Optional<String> primaryGroupOf(UUID player) {
        if (!isPresent()) {
            return Optional.empty();
        }
        try {
            Object user = loadedUser(player);
            return user == null ? Optional.empty() : Optional.of(((String) call(user, "getPrimaryGroup")).toLowerCase(Locale.ROOT));
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn("primary group lookup", e);
            return Optional.empty();
        }
    }

    /** Names of every loaded group. Empty when LuckPerms is absent. */
    public static List<String> groupNames() {
        if (!isPresent()) {
            return Collections.emptyList();
        }
        try {
            Object groupManager = call(api, "getGroupManager");
            Collection<?> groups = (Collection<?>) call(groupManager, "getLoadedGroups");
            Set<String> out = new TreeSet<>();
            for (Object g : groups) {
                out.add((String) call(g, "getName"));
            }
            return List.copyOf(out);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn("group lookup", e);
            return Collections.emptyList();
        }
    }

    /**
     * The groups of a loaded track, first to last. Empty when LuckPerms is absent or the track is not loaded
     * (LuckPerms loads every track at start-up; {@link #ensureTrack} loads the one it makes).
     */
    public static List<String> trackGroups(String track) {
        if (!isPresent()) {
            return Collections.emptyList();
        }
        try {
            Object trackManager = call(api, "getTrackManager");
            Object t = call(trackManager, "getTrack", track);
            return t == null ? Collections.emptyList() : groupsOfTrack(t);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn("track lookup", e);
            return Collections.emptyList();
        }
    }

    /**
     * The furthest group along the track the player is in (directly or by inheritance, so a ladder whose groups
     * inherit each other still answers the top one). Empty when the player is on none of the track's groups.
     */
    public static Optional<String> rankOn(UUID player, String track) {
        List<String> order = trackGroups(track);
        Set<String> mine = groupsOf(player);
        String best = null;
        for (String g : order) {
            if (mine.contains(g.toLowerCase(Locale.ROOT))) {
                best = g;
            }
        }
        return Optional.ofNullable(best);
    }

    /** The player's prefix at the given priority, if any (RankUp keeps its titles at one priority of its own). */
    public static Optional<String> prefixOf(UUID player, int priority) {
        if (!isPresent()) {
            return Optional.empty();
        }
        try {
            Object user = loadedUser(player);
            if (user == null) {
                return Optional.empty();
            }
            String head = "prefix." + priority + ".";
            for (Object n : nodesOf(user)) {
                String key = (String) call(n, "getKey");
                if (key.startsWith(head)) {
                    return Optional.of(key.substring(head.length()));
                }
            }
            return Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn("prefix lookup", e);
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ writes

    /** Moves the player one group up the track (onto its first group when on none). */
    public static CompletableFuture<RankChange> promote(UUID player, String track) {
        return move(player, track, true);
    }

    /** Moves the player one group down the track (off it when on the first group). */
    public static CompletableFuture<RankChange> demote(UUID player, String track) {
        return move(player, track, false);
    }

    private static CompletableFuture<RankChange> move(UUID player, String track, boolean up) {
        if (!isPresent()) {
            return CompletableFuture.completedFuture(new RankChange(Outcome.NO_LUCKPERMS, null, null));
        }
        try {
            return loadTrack(track).thenCompose(t -> {
                if (t == null) {
                    return CompletableFuture.completedFuture(new RankChange(Outcome.NO_TRACK, null, null));
                }
                AtomicReference<RankChange> out = new AtomicReference<>(new RankChange(Outcome.FAILED, null, null));
                Consumer<Object> edit = user -> {
                    try {
                        Object contexts = callStatic(CONTEXT_SET, "empty");
                        Object result = call(t, up ? "promote" : "demote", user, contexts);
                        String status = ((Enum<?>) call(result, "getStatus")).name();
                        String from = optionalString(call(result, "getGroupFrom"));
                        String to = optionalString(call(result, "getGroupTo"));
                        out.set(new RankChange(outcomeOf(status, up), from, to));
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        warn(up ? "promotion" : "demotion", e);
                    }
                };
                return modifyUser(player, edit).thenApply(v -> out.get());
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(up ? "promotion" : "demotion", e);
            return CompletableFuture.completedFuture(new RankChange(Outcome.FAILED, null, null));
        }
    }

    private static Outcome outcomeOf(String status, boolean up) {
        return switch (status) {
            case "SUCCESS" -> up ? Outcome.PROMOTED : Outcome.DEMOTED;
            case "ADDED_TO_FIRST_GROUP" -> Outcome.ADDED_TO_FIRST;
            case "REMOVED_FROM_FIRST_GROUP" -> Outcome.REMOVED_FROM_FIRST;
            case "END_OF_TRACK" -> Outcome.END_OF_TRACK;
            case "NOT_ON_TRACK" -> Outcome.NOT_ON_TRACK;
            default -> Outcome.FAILED;
        };
    }

    /**
     * Puts the player on exactly one group of the track: every other group of the track is removed from the
     * player's own nodes, the given one is added and made the primary group. False when the group or the track
     * does not exist or LuckPerms is absent.
     */
    public static CompletableFuture<Boolean> setGroup(UUID player, String track, String group) {
        if (!isPresent()) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            return loadTrack(track).thenCompose(t -> {
                List<String> onTrack;
                try {
                    onTrack = t == null ? List.of() : groupsOfTrack(t);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    warn("set group", e);
                    return CompletableFuture.completedFuture(false);
                }
                if (!onTrack.contains(group)) {
                    return CompletableFuture.completedFuture(false);
                }
                AtomicBoolean ok = new AtomicBoolean(false);
                Consumer<Object> edit = user -> {
                    try {
                        Object data = call(user, "data");
                        for (Object n : new ArrayList<>(nodesOf(user))) {
                            String key = (String) call(n, "getKey");
                            if (key.startsWith("group.")) {
                                String g = key.substring("group.".length());
                                if (onTrack.contains(g) && !g.equals(group)) {
                                    call(data, "remove", n);
                                }
                            }
                        }
                        Object node = call(callStatic(INHERITANCE_NODE, "builder", group), "build");
                        call(data, "add", node);
                        call(user, "setPrimaryGroup", group); // the stored primary group; what LuckPerms reports also depends on its primary-group-calculation setting
                        ok.set(true);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        warn("set group", e);
                    }
                };
                return modifyUser(player, edit).thenApply(v -> ok.get());
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn("set group", e);
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Sets the player's prefix at the given priority (RankUp's titles), replacing any prefix at that priority;
     * null removes it. Other priorities (the ranks' own prefixes, admin prefixes) are left alone.
     */
    public static CompletableFuture<Boolean> setPrefix(UUID player, int priority, @Nullable String prefix) {
        if (!isPresent()) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            AtomicBoolean ok = new AtomicBoolean(false);
            String head = "prefix." + priority + ".";
            Consumer<Object> edit = user -> {
                try {
                    Object data = call(user, "data");
                    for (Object n : new ArrayList<>(nodesOf(user))) {
                        if (((String) call(n, "getKey")).startsWith(head)) {
                            call(data, "remove", n);
                        }
                    }
                    if (prefix != null) {
                        Object node = call(callStatic(PREFIX_NODE, "builder", prefix, priority), "build");
                        call(data, "add", node);
                    }
                    ok.set(true);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    warn("set prefix", e);
                }
            };
            return modifyUser(player, edit).thenApply(v -> ok.get());
        } catch (RuntimeException e) {
            warn("set prefix", e);
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Makes the track and its groups exist with exactly these groups in this order: missing groups are created,
     * a missing track is created, an existing track's order is replaced. A group this call creates gets a weight
     * of {@value #WEIGHT_STEP} times its position on the ladder, so LuckPerms's usual primary-group setting
     * ({@code parents-by-weight}) reports the player's highest rank as their primary group; existing groups keep
     * their weights. Runs on a background thread. This is the "RankUp makes the LuckPerms track" option (owner
     * decision D2); nothing calls it unless an admin asks.
     */
    public static CompletableFuture<Boolean> ensureTrack(String track, List<String> groups) {
        if (!isPresent()) {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                Object groupManager = call(api, "getGroupManager");
                List<Object> groupObjects = new ArrayList<>();
                for (int i = 0; i < groups.size(); i++) {
                    String name = groups.get(i);
                    Optional<?> loaded = (Optional<?>) join(call(groupManager, "loadGroup", name));
                    if (loaded.isPresent()) {
                        groupObjects.add(loaded.get());
                        continue;
                    }
                    Object created = join(call(groupManager, "createAndLoadGroup", name));
                    Object weight = call(callStatic(WEIGHT_NODE, "builder", WEIGHT_STEP * (i + 1)), "build");
                    call(call(created, "data"), "add", weight);
                    join(call(groupManager, "saveGroup", created));
                    groupObjects.add(created);
                }
                Object trackManager = call(api, "getTrackManager");
                Optional<?> loaded = (Optional<?>) join(call(trackManager, "loadTrack", track));
                Object t = loaded.isPresent() ? loaded.get() : join(call(trackManager, "createAndLoadTrack", track));
                call(t, "clearGroups");
                for (Object g : groupObjects) {
                    call(t, "appendGroup", g);
                }
                join(call(trackManager, "saveTrack", t));
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                warn("ensure track", e);
                return false;
            }
        }, Util.backgroundExecutor());
    }

    // ------------------------------------------------------------------ LuckPerms objects

    @Nullable
    private static Object loadedUser(UUID player) throws ReflectiveOperationException {
        Object userManager = call(api, "getUserManager");
        return call(userManager, "getUser", player);
    }

    private static Collection<?> nodesOf(Object user) throws ReflectiveOperationException {
        return (Collection<?>) call(call(user, "data"), "toCollection");
    }

    @SuppressWarnings("unchecked")
    private static List<String> groupsOfTrack(Object track) throws ReflectiveOperationException {
        return List.copyOf((List<String>) call(track, "getGroups"));
    }

    /** The track, loaded from storage if need be; null when it does not exist. */
    @SuppressWarnings("unchecked")
    private static CompletableFuture<Object> loadTrack(String name) throws ReflectiveOperationException {
        Object trackManager = call(api, "getTrackManager");
        CompletableFuture<Optional<Object>> f = (CompletableFuture<Optional<Object>>) call(trackManager, "loadTrack", name);
        return f.thenApply(o -> o.orElse(null));
    }

    /** Loads the user, applies the edit on a LuckPerms thread and saves. A reflective failure is logged and the future completes without the edit. */
    @SuppressWarnings("unchecked")
    private static CompletableFuture<Void> modifyUser(UUID player, Consumer<Object> edit) {
        try {
            Object userManager = call(api, "getUserManager");
            return (CompletableFuture<Void>) call(userManager, "modifyUser", player, edit);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn("user edit", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static Object join(Object future) {
        return ((CompletableFuture<?>) future).join();
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static String optionalString(Object optional) {
        return ((Optional<String>) optional).orElse(null);
    }

    private static void warn(String what, Exception e) {
        Throwable cause = e instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
        BSPRankUp.LOGGER.warn("LuckPerms {} failed: {}", what, cause.toString());
    }

    // ------------------------------------------------------------------ reflection helpers

    /** Calls a public method by name, chosen by argument count and types, looking through the class and its interfaces. */
    private static Object call(Object target, String method, Object... args) throws ReflectiveOperationException {
        Method m = find(target.getClass(), method, args);
        return m.invoke(target, args);
    }

    /** Calls a public static method of an API class in the LuckPerms class loader. */
    private static Object callStatic(String className, String method, Object... args) throws ReflectiveOperationException {
        if (loader == null) {
            throw new IllegalStateException("LuckPerms not reached");
        }
        Method m = find(loader.loadClass(className), method, args);
        return m.invoke(null, args);
    }

    private static Method find(Class<?> type, String name, Object[] args) throws NoSuchMethodException {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length && accepts(m.getParameterTypes(), args)) {
                try {
                    m.setAccessible(true);
                } catch (RuntimeException ignored) {
                    // a public method of a public type is callable without it
                }
                return m;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name + " with " + args.length + " arguments");
    }

    private static boolean accepts(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            Object a = args[i];
            Class<?> t = types[i];
            if (a == null) {
                if (t.isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (t.isPrimitive()) {
                boolean ok = (t == int.class && a instanceof Integer) || (t == long.class && a instanceof Long) || (t == boolean.class && a instanceof Boolean);
                if (!ok) {
                    return false;
                }
                continue;
            }
            if (!t.isInstance(a)) {
                return false;
            }
        }
        return true;
    }
}
