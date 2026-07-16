package io.github.sarusm.meme.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.sarusm.meme.common.EmoteDef;

/**
 * The player's OWN emote pack: {@code <gameDir>/meme/emotes/{gifs,bubbles,sounds}}, mirroring the
 * server plugin's folder convention (id = image file name — .gif/.png/.jpg, the gif winning a name clash;
 * optional {@code <id>.png} bubble — or {@code default.png} for all — and {@code <id>.ogg} sound; an .ogg
 * NOT attached to an image becomes a standalone sound-only emote). Usable only on servers whose plugin
 * enables {@code player-emotes}: the def + SHA-1 hashes are relayed through the server, never the files.
 *
 * <p>Every scanned asset is imported into the content-addressed {@link AssetCache}, so the whole existing
 * pipeline (textures/sounds looked up by hash) works untouched — and when ANOTHER player plays an emote
 * built from the same files, the hashes match this viewer's cache and the emote just shows.
 *
 * <p>Besides the loose folders, whole packs are read from {@code emotes/packs/*.zip} (same folder
 * conventions inside the zip) — the shareable pack format; the loose folders win id clashes. The starter
 * pack bundled in the mod jar is dropped there on first launch, which also pre-caches its assets, so on
 * servers running the same pack nothing needs downloading at all.
 *
 * <p>Rescans on join and on panel open; unchanged files/zips (same size+mtime) are not re-hashed.
 */
public final class LocalEmotes {
    /** Mirror of the server's max-asset-mb default — oversized files are skipped with a log. */
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    private record FileSig(long size, long mtime, String hash, int byteCount) {
    }

    /** Hash memo keyed by file path — a rescan of an unchanged folder does no hashing at all. */
    private static final Map<Path, FileSig> SIGS = new HashMap<>();

    private record ZipPack(long size, long mtime, Map<String, EmoteDef> defs) {
    }

    /** Parsed-pack memo keyed by zip path — an unchanged zip (assets still cached) is not re-read. */
    private static final Map<Path, ZipPack> ZIP_SIGS = new HashMap<>();

    /** Replaced wholesale by rescan (possibly off-thread — see the init prewarm); volatile for readers. */
    private static volatile Map<String, EmoteDef> emotes = new LinkedHashMap<>();
    /** Signature of the last scan result — revision only bumps when the set actually changed. */
    private static String lastSignature = "";

    private LocalEmotes() {
    }

    public static Path dir() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                .resolve("meme").resolve("emotes");
    }

    /** Shareable zip emote packs: the same gifs/bubbles/sounds folder conventions inside each zip. */
    public static Path packsDir() {
        return dir().resolve("packs");
    }

    /**
     * First launch only: drop the starter pack bundled in the mod jar (see build.gradle) into the packs
     * folder. "First launch" = the packs dir does not exist; once it does, nothing is re-installed —
     * deleting meme-pack.zip is a choice that sticks.
     */
    public static void installBundledPack() {
        Path packs = packsDir();
        if (Files.exists(packs)) {
            return;
        }
        try {
            Files.createDirectories(packs);
            try (InputStream in = LocalEmotes.class.getResourceAsStream("/meme-pack.zip")) {
                if (in == null) {
                    return; // jar built without the bundled pack
                }
                Files.copy(in, packs.resolve("meme-pack.zip"));
                MemeClient.LOGGER.info("Installed the starter emote pack: {}",
                        packs.resolve("meme-pack.zip"));
            }
        } catch (IOException e) {
            MemeClient.LOGGER.warn("Could not install the bundled emote pack", e);
        }
    }

    public static EmoteDef get(String id) {
        return emotes.get(id);
    }

    public static List<EmoteDef> all() {
        return List.copyOf(emotes.values());
    }

    /** Re-scan the pack folders (cheap when nothing changed) and bump the catalogue revision on changes. */
    public static synchronized void rescan() {
        Path gifs = dir().resolve("gifs");
        Path bubbles = dir().resolve("bubbles");
        Path sounds = dir().resolve("sounds");
        try {
            Files.createDirectories(gifs);
            Files.createDirectories(bubbles);
            Files.createDirectories(sounds);
            Files.createDirectories(packsDir());
        } catch (IOException e) {
            MemeClient.LOGGER.warn("Could not create the local emote pack folders", e);
            return;
        }

        FileSig defaultBubble = imported(bubbles.resolve("default.png"));

        // Image files by id, extensions in preference order (a gif beats a same-named png).
        Map<String, Path> images = new TreeMap<>();
        try {
            for (String ext : new String[] {".gif", ".png", ".jpg", ".jpeg"}) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(gifs, "*" + ext)) {
                    for (Path image : stream) {
                        String id = idFrom(image, ext);
                        if (id != null) {
                            images.putIfAbsent(id, image);
                        }
                    }
                }
            }
        } catch (IOException e) {
            MemeClient.LOGGER.warn("Could not scan the local emote pack", e);
            return;
        }

        Map<String, EmoteDef> found = new TreeMap<>();
        for (Map.Entry<String, Path> entry : images.entrySet()) {
            String id = entry.getKey();
            FileSig imageSig = imported(entry.getValue());
            if (imageSig == null) {
                continue;
            }
            EmoteDef def = baseDef(id, bubbles, sounds, defaultBubble);
            def.gifHash = imageSig.hash();
            def.gifSize = imageSig.byteCount();
            found.put(id, def);
        }

        // Every .ogg without a same-named image is its own sound-only emote (name shown as text).
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sounds, "*.ogg")) {
            for (Path ogg : stream) {
                String id = idFrom(ogg, ".ogg");
                if (id == null || found.containsKey(id)) {
                    continue; // attached to an image emote (or unusable name)
                }
                EmoteDef def = baseDef(id, bubbles, sounds, defaultBubble);
                if (!def.soundHash.isEmpty()) {
                    found.put(id, def);
                }
            }
        } catch (IOException e) {
            MemeClient.LOGGER.warn("Could not scan the local emote pack", e);
            return;
        }

        scanPacks(found);

        StringBuilder signature = new StringBuilder();
        for (EmoteDef def : found.values()) {
            signature.append(def.id).append('|').append(def.gifHash).append('|')
                    .append(def.bubbleHash).append('|').append(def.soundHash).append('\n');
        }
        emotes = new LinkedHashMap<>(found);
        if (!signature.toString().equals(lastSignature)) {
            lastSignature = signature.toString();
            MemeClient.LOGGER.info("Local emote pack: {} emotes", emotes.size());
            ClientEmotes.bumpRevision();
        }
    }

    /** Merge the emotes of every zip in {@code packs/} into {@code found} — existing ids (the loose
     *  folders, earlier zips alphabetically) win. */
    private static void scanPacks(Map<String, EmoteDef> found) {
        List<Path> zips = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(packsDir(), "*.zip")) {
            stream.forEach(zips::add);
        } catch (IOException e) {
            MemeClient.LOGGER.warn("Could not list the emote pack zips", e);
            return;
        }
        zips.sort(Comparator.comparing(p -> p.getFileName().toString()));
        ZIP_SIGS.keySet().retainAll(new HashSet<>(zips));
        for (Path zip : zips) {
            for (EmoteDef def : packDefs(zip).values()) {
                found.putIfAbsent(def.id, def);
            }
        }
    }

    /** The zip's emotes, from the memo when the zip is unchanged and its assets are still cached. */
    private static Map<String, EmoteDef> packDefs(Path zip) {
        long size;
        long mtime;
        try {
            size = Files.size(zip);
            mtime = Files.getLastModifiedTime(zip).toMillis();
        } catch (IOException e) {
            return Map.of();
        }
        ZipPack memo = ZIP_SIGS.get(zip);
        if (memo != null && memo.size() == size && memo.mtime() == mtime && allCached(memo.defs())) {
            return memo.defs();
        }
        Map<String, EmoteDef> defs = parsePack(zip);
        ZIP_SIGS.put(zip, new ZipPack(size, mtime, defs));
        return defs;
    }

    private static boolean allCached(Map<String, EmoteDef> defs) {
        for (EmoteDef def : defs.values()) {
            if ((!def.gifHash.isEmpty() && !AssetCache.has(def.gifHash))
                    || (!def.bubbleHash.isEmpty() && !AssetCache.has(def.bubbleHash))
                    || (!def.soundHash.isEmpty() && !AssetCache.has(def.soundHash))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Read one pack zip, importing every asset into the {@link AssetCache}. Inside the zip the server
     * folder conventions apply unchanged — {@code gifs/}, {@code bubbles/} ({@code <id>.png} or
     * {@code default.png}), {@code sounds/} — at the zip root or nested under one wrapping folder.
     */
    private static Map<String, EmoteDef> parsePack(Path zip) {
        Map<String, EmoteDef> out = new TreeMap<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Map<String, ZipEntry> gifs = new HashMap<>();
            Map<String, ZipEntry> bubbles = new HashMap<>();
            Map<String, ZipEntry> sounds = new HashMap<>();
            for (var it = zf.entries(); it.hasMoreElements(); ) {
                ZipEntry entry = it.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String path = entry.getName().replace('\\', '/');
                int slash = path.lastIndexOf('/');
                if (slash <= 0) {
                    continue; // a file at the zip root belongs to no category
                }
                String dir = path.substring(0, slash);
                String parent = dir.substring(dir.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                String fileName = path.substring(slash + 1).toLowerCase(Locale.ROOT);
                switch (parent) {
                    case "gifs" -> gifs.putIfAbsent(fileName, entry);
                    case "bubbles" -> bubbles.putIfAbsent(fileName, entry);
                    case "sounds" -> sounds.putIfAbsent(fileName, entry);
                    default -> { }
                }
            }

            FileSig defaultBubble = importedEntry(zf, bubbles.get("default.png"), zip);

            // Image emotes, extensions in preference order (a gif beats a same-named png).
            Map<String, ZipEntry> images = new TreeMap<>();
            for (String ext : new String[] {".gif", ".png", ".jpg", ".jpeg"}) {
                for (Map.Entry<String, ZipEntry> file : gifs.entrySet()) {
                    String id = zipId(file.getKey(), ext);
                    if (id != null) {
                        images.putIfAbsent(id, file.getValue());
                    }
                }
            }
            for (Map.Entry<String, ZipEntry> image : images.entrySet()) {
                String id = image.getKey();
                FileSig imageSig = importedEntry(zf, image.getValue(), zip);
                if (imageSig == null) {
                    continue;
                }
                EmoteDef def = zipDef(id, zf, zip, bubbles, sounds, defaultBubble);
                def.gifHash = imageSig.hash();
                def.gifSize = imageSig.byteCount();
                out.put(id, def);
            }

            // Every .ogg without a same-named image is its own sound-only emote.
            for (Map.Entry<String, ZipEntry> file : sounds.entrySet()) {
                String id = zipId(file.getKey(), ".ogg");
                if (id == null || images.containsKey(id) || out.containsKey(id)) {
                    continue;
                }
                EmoteDef def = zipDef(id, zf, zip, bubbles, sounds, defaultBubble);
                if (!def.soundHash.isEmpty()) {
                    out.put(id, def);
                }
            }
            if (!out.isEmpty()) {
                MemeClient.LOGGER.info("Emote pack {}: {} emotes", zip.getFileName(), out.size());
            }
        } catch (IOException e) {
            MemeClient.LOGGER.warn("Could not read emote pack {}", zip.getFileName(), e);
        }
        return out;
    }

    /** The emote id from a zip entry file name (already lowercased); null when not {@code ext}/unusable. */
    private static String zipId(String fileName, String ext) {
        if (!fileName.endsWith(ext)) {
            return null;
        }
        String id = fileName.substring(0, fileName.length() - ext.length()).strip();
        return id.isEmpty() || id.length() > 48 ? null : id;
    }

    /** {@link #baseDef} for a pack zip: bubble ({@code <id>.png} or default) + {@code <id>.ogg}. */
    private static EmoteDef zipDef(String id, ZipFile zf, Path zip, Map<String, ZipEntry> bubbles,
            Map<String, ZipEntry> sounds, FileSig defaultBubble) {
        EmoteDef def = new EmoteDef();
        def.id = id;
        def.name = id;
        FileSig bubbleSig = importedEntry(zf, bubbles.get(id + ".png"), zip);
        if (bubbleSig == null) {
            bubbleSig = defaultBubble;
        }
        if (bubbleSig != null) {
            def.bubbleHash = bubbleSig.hash();
            def.bubbleSize = bubbleSig.byteCount();
        }
        FileSig soundSig = importedEntry(zf, sounds.get(id + ".ogg"), zip);
        if (soundSig != null) {
            def.soundHash = soundSig.hash();
            def.soundSize = soundSig.byteCount();
        }
        def.canUse = true;
        return def;
    }

    /** Import one zip entry into the {@link AssetCache}; null when absent, oversized or unreadable. */
    private static FileSig importedEntry(ZipFile zf, ZipEntry entry, Path zip) {
        if (entry == null) {
            return null;
        }
        if (entry.getSize() > MAX_FILE_BYTES) {
            MemeClient.LOGGER.warn("Pack asset {}!{} skipped ({} KB > {} KB limit)",
                    zip.getFileName(), entry.getName(), entry.getSize() / 1024, MAX_FILE_BYTES / 1024);
            return null;
        }
        try (InputStream in = zf.getInputStream(entry)) {
            byte[] data = in.readNBytes((int) MAX_FILE_BYTES + 1);
            if (data.length == 0 || data.length > MAX_FILE_BYTES) {
                return null;
            }
            String hash = AssetCache.sha1(data);
            if (!AssetCache.has(hash) && !AssetCache.put(hash, data)) {
                return null;
            }
            return new FileSig(data.length, 0, hash, data.length);
        } catch (IOException e) {
            MemeClient.LOGGER.warn("Could not read pack asset {}!{}",
                    zip.getFileName(), entry.getName(), e);
            return null;
        }
    }

    /** The emote id from a pack file name (minus {@code ext}); null when empty or absurdly long. */
    private static String idFrom(Path file, String ext) {
        String fileName = file.getFileName().toString();
        String id = fileName.substring(0, fileName.length() - ext.length())
                .toLowerCase(Locale.ROOT).strip();
        return id.isEmpty() || id.length() > 48 ? null : id;
    }

    /** A def with the shared conventions resolved: bubble ({@code <id>.png} or default) + {@code <id>.ogg}. */
    private static EmoteDef baseDef(String id, Path bubbles, Path sounds, FileSig defaultBubble) {
        EmoteDef def = new EmoteDef(); // placement/sound tuning stays at the EmoteDef defaults
        def.id = id;
        def.name = id;
        FileSig bubbleSig = imported(bubbles.resolve(id + ".png"));
        if (bubbleSig == null) {
            bubbleSig = defaultBubble;
        }
        if (bubbleSig != null) {
            def.bubbleHash = bubbleSig.hash();
            def.bubbleSize = bubbleSig.byteCount();
        }
        FileSig soundSig = imported(sounds.resolve(id + ".ogg"));
        if (soundSig != null) {
            def.soundHash = soundSig.hash();
            def.soundSize = soundSig.byteCount();
        }
        def.canUse = true;
        return def;
    }

    /**
     * The file's content hash + size, importing it into the {@link AssetCache} when it isn't there yet;
     * null when the file is absent, oversized or unreadable. Unchanged files reuse the memoised hash.
     */
    private static FileSig imported(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                SIGS.remove(file);
                return null;
            }
            long size = Files.size(file);
            long mtime = Files.getLastModifiedTime(file).toMillis();
            if (size <= 0 || size > MAX_FILE_BYTES) {
                MemeClient.LOGGER.warn("Local emote asset {} skipped ({} KB > {} KB limit)",
                        file.getFileName(), size / 1024, MAX_FILE_BYTES / 1024);
                return null;
            }
            FileSig sig = SIGS.get(file);
            if (sig != null && sig.size() == size && sig.mtime() == mtime && AssetCache.has(sig.hash())) {
                return sig;
            }
            byte[] data = Files.readAllBytes(file);
            String hash = AssetCache.sha1(data);
            if (!AssetCache.has(hash) && !AssetCache.put(hash, data)) {
                return null;
            }
            sig = new FileSig(size, mtime, hash, data.length);
            SIGS.put(file, sig);
            return sig;
        } catch (IOException e) {
            MemeClient.LOGGER.warn("Could not read local emote asset {}", file, e);
            return null;
        }
    }
}
