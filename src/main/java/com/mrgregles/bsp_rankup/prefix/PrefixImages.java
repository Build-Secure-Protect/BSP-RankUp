package com.mrgregles.bsp_rankup.prefix;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.network.PrefixImagePacket;
import com.mrgregles.bsp_rankup.network.RankUpNetwork;
import com.mrgregles.bsp_rankup.settings.PrefixImage;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsFiles;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.storage.Storage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The prefix images (owner decisions D58, D59, D62, D63, D71): PNG files an admin puts in
 * {@code config/bsp_rankup/prefix_images/}, checked when the folder is read, and the bytes of every image picked for
 * a rank, kept on this server (saved with the overworld) and in the shared storage, so the other servers have them
 * without the file. Players are sent each image in use at login and when one is picked; an admin with the screen
 * open is also sent the folder's files for the previews. Images are known by the hash of their bytes, so the same
 * file is one image however many ranks use it (D70).
 */
public final class PrefixImages {
    /** The largest file taken (D58): 256 x 64 pixels and 256 KB. */
    public static final int MAX_WIDTH = 256, MAX_HEIGHT = 64, MAX_BYTES = 256 * 1024;
    /** Drawn one text line high, at most 64 pixels wide (D60). */
    public static final int LINE = 9, MAX_DRAWN_WIDTH = 64;
    /** The most files read from the folder. */
    public static final int MAX_FILES = 100;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /**
     * A file in the folder: its name, size in pixels and bytes, its id (the hash) and what is wrong with it (empty
     * when it can be used).
     */
    public record FileInfo(String name, int width, int height, int bytes, String id, String problem) {
        public boolean usable() {
            return problem.isEmpty();
        }
    }

    private static final Set<MinecraftServer> SERVERS = Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** The folder as last read, and the bytes of its usable files by id. */
    private static List<FileInfo> files = List.of();
    private static Map<String, byte[]> fileBytes = Map.of();
    /** Each file as last read, by name with its size and time: a file that has not changed is not read or decoded again. */
    private record Read(long size, long modified, FileInfo info, @Nullable byte[] png) {
    }

    private static Map<String, Read> reads = Map.of();
    private static boolean scanned;
    /** Images asked of the shared storage and not answered yet. */
    private static final Set<String> FETCHING = new HashSet<>();
    /** The images each player has been sent since they logged in. */
    private static final Map<UUID, Set<String>> SENT = new HashMap<>();
    /** The ids the settings used before the last change, to send only the new ones. */
    private static Set<String> lastIds = Set.of();
    /** Tests: the storage images go to and come from in place of the active one. */
    @Nullable
    private static Storage testStorage;

    private PrefixImages() {
    }

    public static void init() {
        SettingsLedger.onChange(PrefixImages::onSettingsChanged);
    }

    public static void onServerStarted(MinecraftServer server) {
        SERVERS.add(server);
        lastIds = ids(SettingsLedger.settings(server));
        lastSignature = drawnSignature(SettingsLedger.settings(server));
        fetchMissing(server);
    }

    public static void onServerStopping(MinecraftServer server) {
        SERVERS.remove(server);
        FETCHING.clear();
        SENT.clear();
    }

    public static Path folder() {
        return SettingsFiles.directory().resolve("prefix_images");
    }

    // ------------------------------------------------------------------ the folder

    /** The folder as last read; read now if it never was. */
    public static List<FileInfo> files() {
        if (!scanned) {
            scan();
        }
        return files;
    }

    /** Reads the folder again (REFRESH, D59), making it when missing. Files are sorted by name. */
    public static List<FileInfo> scan() {
        scanned = true;
        Path dir = folder();
        List<FileInfo> out = new ArrayList<>();
        Map<String, byte[]> bytes = new HashMap<>();
        Map<String, Read> seen = new HashMap<>();
        try {
            Files.createDirectories(dir);
            List<Path> paths = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.{png,PNG}")) {
                for (Path p : stream) {
                    if (Files.isRegularFile(p)) {
                        paths.add(p);
                    }
                }
            }
            paths.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
            for (Path p : paths.subList(0, Math.min(MAX_FILES, paths.size()))) {
                String name = p.getFileName().toString();
                try {
                    long size = Files.size(p), modified = Files.getLastModifiedTime(p).toMillis();
                    Read before = reads.get(name);
                    Read read;
                    if (before != null && before.size() == size && before.modified() == modified) {
                        read = before;
                    } else if (size > MAX_BYTES) {
                        read = new Read(size, modified, new FileInfo(name, 0, 0, (int) Math.min(Integer.MAX_VALUE, size), "", "over 256 KB"), null);
                    } else {
                        byte[] png = Files.readAllBytes(p);
                        FileInfo info = check(name, png);
                        read = new Read(size, modified, info, info.usable() ? png : null);
                    }
                    seen.put(name, read);
                    out.add(read.info());
                    if (read.png() != null) {
                        bytes.put(read.info().id(), read.png());
                    }
                } catch (IOException | RuntimeException e) {
                    out.add(new FileInfo(name, 0, 0, 0, "", "unreadable"));
                }
            }
        } catch (IOException e) {
            BSPRankUp.LOGGER.warn("RankUp could not read {}: {}", dir, e.toString());
        }
        files = List.copyOf(out);
        fileBytes = Map.copyOf(bytes);
        reads = Map.copyOf(seen);
        return files;
    }

    /** The file by name as last read, or null. */
    @Nullable
    public static FileInfo file(String name) {
        for (FileInfo f : files()) {
            if (f.name().equalsIgnoreCase(name)) {
                return f;
            }
        }
        return null;
    }

    /** A usable file's bytes as last read, by id, or null. */
    @Nullable
    public static byte[] folderBytes(String id) {
        return fileBytes.get(id);
    }

    /** Whether the folder as last read holds an image with this id (D71: "not in folder" otherwise). */
    public static boolean inFolder(String id) {
        return fileBytes.containsKey(id);
    }

    /**
     * Checks a file's bytes against D58 and D60: a PNG, not animated, at most 256 x 64 pixels, no wider than 64
     * pixels once drawn one line high, and readable. Returns its size and hash, or the problem in a few words.
     */
    public static FileInfo check(String name, byte[] png) {
        if (png.length > MAX_BYTES) {
            return new FileInfo(name, 0, 0, png.length, "", "over 256 KB");
        }
        if (png.length < 33 || !java.util.Arrays.equals(png, 0, 8, PNG, 0, 8)) {
            return new FileInfo(name, 0, 0, png.length, "", "not a PNG");
        }
        ByteBuffer buf = ByteBuffer.wrap(png);
        int width = buf.getInt(16), height = buf.getInt(20);
        // an animated PNG has an acTL chunk before its image data (D58: no animation)
        int at = 8;
        while (at + 8 <= png.length) {
            int length = buf.getInt(at);
            String type = new String(png, at + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("acTL")) {
                return new FileInfo(name, width, height, png.length, "", "animated");
            }
            if (length < 0 || length > png.length - at - 12) {
                return new FileInfo(name, width, height, png.length, "", "broken"); // a chunk longer than the file
            }
            if (type.equals("IDAT")) {
                break;
            }
            at += 12 + length;
        }
        if (width <= 0 || height <= 0) {
            return new FileInfo(name, width, height, png.length, "", "broken");
        }
        if (width > MAX_WIDTH || height > MAX_HEIGHT) {
            return new FileInfo(name, width, height, png.length, "", width + " x " + height + ": over 256 x 64");
        }
        if (drawnWidth(width, height) > MAX_DRAWN_WIDTH) {
            return new FileInfo(name, width, height, png.length, "", "too wide: " + drawnWidth(width, height) + " px in chat, over 64");
        }
        try {
            if (ImageIO.read(new ByteArrayInputStream(png)) == null) {
                return new FileInfo(name, width, height, png.length, "", "broken");
            }
        } catch (IOException | RuntimeException e) {
            return new FileInfo(name, width, height, png.length, "", "broken");
        }
        return new FileInfo(name, width, height, png.length, idOf(png), "");
    }

    /** How wide an image is drawn one text line high (D60). */
    public static int drawnWidth(int width, int height) {
        return height <= 0 ? 0 : (int) Math.ceil((double) width * LINE / height);
    }

    /** An image's id: the SHA-1 of its bytes, 40 hex digits. */
    public static String idOf(byte[] png) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(png));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ the images in use

    /** An image's bytes: kept here, or the folder's copy; null when neither has it. */
    @Nullable
    public static byte[] bytes(MinecraftServer server, String id) {
        byte[] kept = Store.get(server).images.get(id);
        return kept != null ? kept : fileBytes.get(id);
    }

    /** Keeps an image here and in the shared storage; the folder's file may go afterwards (D71). */
    public static void keep(MinecraftServer server, String id, byte[] png) {
        Store.get(server).put(id, png);
        Storage s = storage();
        if (s != null) {
            s.saveImage(id, png);
        }
    }

    /** The ids every prefix image in the settings uses. */
    public static Set<String> ids(Settings settings) {
        Set<String> out = new LinkedHashSet<>();
        settings.prefixImages().values().forEach(i -> out.add(i.id()));
        return out;
    }

    /** What the names drawn depend on: the switch, the images and every title's text. */
    private static String drawnSignature(Settings settings) {
        StringBuilder sb = new StringBuilder().append(settings.showPrefixes()).append('|').append(settings.prefixImages());
        for (PrefixDisplay.Source s : PrefixDisplay.sources(settings, Map.of())) {
            sb.append('|').append(s.key()).append('=').append(s.text());
        }
        return sb.toString();
    }

    @Nullable
    private static String lastSignature;

    private static void onSettingsChanged(Settings settings) {
        String signature = drawnSignature(settings);
        boolean redraw = !signature.equals(lastSignature);
        lastSignature = signature;
        Set<String> now = ids(settings);
        Set<String> added = new LinkedHashSet<>(now);
        added.removeAll(lastIds);
        lastIds = now;
        for (MinecraftServer server : List.copyOf(SERVERS)) {
            Store.get(server).keepOnly(now);
            fetchMissing(server);
            for (String id : added) {
                sendToAll(server, id);
            }
            if (redraw) {
                PrefixDisplay.refreshAll(server); // a picked image, a title's text or the switch: everyone's name is drawn again
            }
        }
    }

    /** Asks the shared storage for every image in use that this server lacks; each is sent to everyone on arrival. */
    public static void fetchMissing(MinecraftServer server) {
        Storage s = storage();
        for (String id : ids(SettingsLedger.settings(server))) {
            if (bytes(server, id) != null || s == null || !FETCHING.add(id)) {
                continue;
            }
            s.loadImage(id, png -> {
                FETCHING.remove(id);
                if (png == null) {
                    BSPRankUp.LOGGER.warn("RankUp prefix image {} is in the settings but in no storage; its text prefix shows until it is picked again", id);
                    return;
                }
                if (!idOf(png).equals(id)) {
                    BSPRankUp.LOGGER.warn("RankUp prefix image {} came back changed from the shared storage; not used", id);
                    return;
                }
                Store.get(server).put(id, png);
                sendToAll(server, id);
            });
        }
    }

    // ------------------------------------------------------------------ sending to players (D62)

    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SENT.remove(player.getUUID());
            for (String id : ids(SettingsLedger.settings(player.server))) {
                send(player, id);
            }
        }
    }

    public static void forget(UUID player) {
        SENT.remove(player);
    }

    /** Sends an image to a player once per login; false when this server does not have it. */
    public static boolean send(ServerPlayer player, String id) {
        if (SENT.getOrDefault(player.getUUID(), Set.of()).contains(id)) {
            return true;
        }
        byte[] png = bytes(player.server, id);
        if (png == null) {
            return false;
        }
        SENT.computeIfAbsent(player.getUUID(), k -> new HashSet<>()).add(id);
        RankUpNetwork.sendTo(player, new PrefixImagePacket(id, png));
        return true;
    }

    /** Sends an admin the folder's usable files, for the PREFIXES tab's previews. */
    public static void sendFiles(ServerPlayer admin) {
        for (FileInfo f : files()) {
            if (f.usable()) {
                send(admin, f.id());
            }
        }
    }

    private static void sendToAll(MinecraftServer server, String id) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            send(p, id);
        }
    }

    @Nullable
    private static Storage storage() {
        return testStorage != null ? testStorage : Storage.active();
    }

    /** Tests: images go to and come from this storage (null: the active one again). */
    public static void useStorage(@Nullable Storage storage) {
        testStorage = storage;
    }

    /** Tests: forgets the images kept on this server, as a server that never had them. */
    public static void forgetKept(MinecraftServer server) {
        Store.get(server).keepOnly(Set.of());
    }

    /** Whether a player was sent an image this login (tests). */
    public static boolean wasSent(UUID player, String id) {
        return SENT.getOrDefault(player, Set.of()).contains(id);
    }

    /** The images in use kept on this server, saved with the overworld ({@code bsp_rankup_prefix_images}). */
    public static final class Store extends SavedData {
        public static final String NAME = "bsp_rankup_prefix_images";
        private final Map<String, byte[]> images = new HashMap<>();

        public static Store get(MinecraftServer server) {
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            if (overworld == null) {
                throw new IllegalStateException("no overworld");
            }
            return overworld.getDataStorage().computeIfAbsent(Store::load, Store::new, NAME);
        }

        void put(String id, byte[] png) {
            if (!images.containsKey(id)) {
                images.put(id, png.clone());
                setDirty();
            }
        }

        /** Drops the images no rank uses any more (the shared table keeps them). */
        void keepOnly(Set<String> ids) {
            if (images.keySet().retainAll(ids)) {
                setDirty();
            }
        }

        public boolean has(String id) {
            return images.containsKey(id);
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            CompoundTag list = new CompoundTag();
            images.forEach(list::putByteArray);
            tag.put("Images", list);
            return tag;
        }

        public static Store load(CompoundTag tag) {
            Store store = new Store();
            CompoundTag list = tag.getCompound("Images");
            for (String id : list.getAllKeys()) {
                store.images.put(id.toLowerCase(Locale.ROOT), list.getByteArray(id));
            }
            return store;
        }
    }
}
