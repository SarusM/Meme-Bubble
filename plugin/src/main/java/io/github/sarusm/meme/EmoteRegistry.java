package io.github.sarusm.meme;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Loads the server's emote catalogue: every image ({@code .gif}, {@code .png}, {@code .jpg}/{@code .jpeg})
 * in {@code plugins/Meme/emotes/gifs/} becomes an emote (id = lowercased file name; on a name clash
 * the gif wins), optionally customised by a section in {@code emotes.yml}. Convention over configuration:
 * a bubble named {@code <id>.png} or a sound named {@code <id>.ogg} attaches automatically; otherwise the
 * config keys / the default bubble apply. Every {@code .ogg} in {@code emotes/sounds/} NOT attached to an
 * image emote becomes a standalone sound-only emote (the client shows its name as text in the bubble).
 *
 * <p>Assets are content-addressed: each file's SHA-1 is computed here and {@link #assetByHash} answers the
 * client's download requests. Files above {@code max-asset-mb} are skipped with a console warning.
 */
public final class EmoteRegistry {
    /** A servable asset behind a content hash: a loose file in emotes/, or an entry inside a pack zip. */
    public static final class Asset {
        /** The loose file itself, or the .zip containing {@link #entry}. */
        private final File file;
        /** Zip entry name; null for a loose file. */
        private final String entry;

        Asset(File file, String entry) {
            this.file = file;
            this.entry = entry;
        }

        public String name() {
            return entry == null ? file.getName() : file.getName() + "!" + entry;
        }

        public byte[] read() throws IOException {
            if (entry == null) {
                return Files.readAllBytes(file.toPath());
            }
            try (ZipFile zip = new ZipFile(file)) {
                ZipEntry e = zip.getEntry(entry);
                if (e == null) {
                    throw new IOException("entry " + entry + " vanished from " + file.getName());
                }
                try (InputStream in = zip.getInputStream(e)) {
                    return in.readAllBytes();
                }
            }
        }
    }

    private record Hashed(String hash, int size) {
    }

    /** One choosable speech bubble offered to clients (the bubble picker). */
    public record BubbleOption(String hash, String name, int size) {
    }

    private final MemePlugin plugin;
    private final Map<String, ServerEmote> emotes = new LinkedHashMap<>();
    private final Map<String, Asset> assetsByHash = new HashMap<>();
    /** The choosable bubble catalogue (every bubble PNG in loose bubbles/ + each pack), keyed by hash. */
    private final Map<String, BubbleOption> bubblesByHash = new LinkedHashMap<>();

    private File emotesYmlFile;
    private YamlConfiguration emotesYml;

    public EmoteRegistry(MemePlugin plugin) {
        this.plugin = plugin;
    }

    public File gifsDir() {
        return new File(plugin.getDataFolder(), "emotes/gifs");
    }

    public File bubblesDir() {
        return new File(plugin.getDataFolder(), "emotes/bubbles");
    }

    public File soundsDir() {
        return new File(plugin.getDataFolder(), "emotes/sounds");
    }

    /** Shareable zip emote packs (gifs/bubbles/sounds folders inside the zip, same conventions). */
    public File packsDir() {
        return new File(plugin.getDataFolder(), "emotes/packs");
    }

    /** (Re)scan the folders and re-read emotes.yml. */
    public void load() {
        emotes.clear();
        assetsByHash.clear();
        bubblesByHash.clear();
        gifsDir().mkdirs();
        bubblesDir().mkdirs();
        soundsDir().mkdirs();
        packsDir().mkdirs();

        emotesYmlFile = new File(plugin.getDataFolder(), "emotes.yml");
        if (!emotesYmlFile.exists()) {
            plugin.saveResource("emotes.yml", false);
        }
        emotesYml = YamlConfiguration.loadConfiguration(emotesYmlFile);
        ConfigurationSection all = emotesYml.getConfigurationSection("emotes");
        // Baseline tuning applied to EVERY emote before its own section (per-emote keys win).
        ConfigurationSection defaults = emotesYml.getConfigurationSection("defaults");

        long maxBytes = plugin.getConfig().getLong("max-asset-mb", 8) * 1024L * 1024L;
        String defaultBubble = plugin.getConfig().getString("default-bubble", "");

        // Sorted so the client panel is stable across restarts. Extensions scanned in preference
        // order — on an id clash (hello.gif + hello.png) the gif wins.
        Map<String, File> images = new TreeMap<>();
        for (String ext : new String[] {".gif", ".png", ".jpg", ".jpeg"}) {
            File[] files = gifsDir().listFiles((dir, n) -> n.toLowerCase(Locale.ROOT).endsWith(ext));
            if (files == null) {
                continue;
            }
            for (File f : files) {
                String base = f.getName().substring(0, f.getName().length() - ext.length());
                images.putIfAbsent(base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_"), f);
            }
        }

        for (Map.Entry<String, File> entry : images.entrySet()) {
            ServerEmote e = buildEmote(entry.getKey(), entry.getValue(), null,
                    all, defaults, defaultBubble, maxBytes);
            if (e != null) {
                emotes.put(e.id, e);
            }
        }

        // Every .ogg NOT attached to an image emote is a standalone sound-only emote.
        Set<String> usedSounds = new HashSet<>();
        for (ServerEmote e : emotes.values()) {
            if (e.soundFile != null) {
                usedSounds.add(e.soundFile.getName().toLowerCase(Locale.ROOT));
            }
        }
        Map<String, File> soloSounds = new TreeMap<>();
        File[] oggFiles = soundsDir().listFiles((dir, n) -> n.toLowerCase(Locale.ROOT).endsWith(".ogg"));
        if (oggFiles != null) {
            for (File f : oggFiles) {
                if (usedSounds.contains(f.getName().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String base = f.getName().substring(0, f.getName().length() - ".ogg".length());
                soloSounds.putIfAbsent(base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_"), f);
            }
        }
        for (Map.Entry<String, File> entry : soloSounds.entrySet()) {
            if (emotes.containsKey(entry.getKey())) {
                continue; // an image emote already owns this id
            }
            ServerEmote e = buildEmote(entry.getKey(), null, entry.getValue(),
                    all, defaults, defaultBubble, maxBytes);
            if (e != null) {
                emotes.put(e.id, e);
            }
        }

        scanLooseBubbles(maxBytes);
        loadPacks(all, defaults, defaultBubble, maxBytes);

        plugin.getLogger().info("Loaded " + emotes.size() + " emotes ("
                + assetsByHash.size() + " asset files, " + bubblesByHash.size() + " bubbles)");
    }

    /** Register every image in {@code emotes/bubbles/} ({@code .png}/{@code .jpg}/{@code .jpeg}) in the
     *  choosable bubble catalogue (loose files win the display name on a content clash, being scanned
     *  before packs). */
    private void scanLooseBubbles(long maxBytes) {
        File[] files = bubblesDir().listFiles((dir, n) -> {
            String low = n.toLowerCase(Locale.ROOT);
            return low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg");
        });
        if (files == null) {
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            String hash = hashFile(f, maxBytes);
            if (hash != null) {
                bubblesByHash.putIfAbsent(hash, new BubbleOption(hash, f.getName(), (int) f.length()));
            }
        }
    }

    /** Register the bubble PNGs inside one pack zip in the choosable catalogue. */
    private void scanPackBubbles(File zip, ZipFile zf, Map<String, String> bubbles, long maxBytes) {
        for (Map.Entry<String, String> entry : new TreeMap<>(bubbles).entrySet()) {
            Hashed h = hashZipEntry(zip, zf, entry.getValue(), maxBytes);
            if (h != null) {
                bubblesByHash.putIfAbsent(h.hash(), new BubbleOption(h.hash(), entry.getKey(), h.size()));
            }
        }
    }

    /** The choosable bubble catalogue sent to clients (the bubble picker). */
    public Collection<BubbleOption> bubbleCatalogue() {
        return bubblesByHash.values();
    }

    /** Is {@code hash} a bubble the catalogue offers? (validates a client's C2S_SET_BUBBLE.) */
    public boolean isBubbleHash(String hash) {
        return bubblesByHash.containsKey(hash);
    }

    /**
     * Emotes from the zip packs in {@code emotes/packs/} — the shareable pack format. Inside a zip the
     * folder conventions apply unchanged ({@code gifs/}, {@code bubbles/} with a per-emote {@code <id>.png}
     * or the {@code default-bubble} file, {@code sounds/}), at the zip root or nested under one wrapping
     * folder. The pack-wide fallback bubble is whatever {@code default-bubble} (config.yml) names, looked
     * up inside the pack; an empty {@code default-bubble} or a name the pack lacks means no bubble.
     * Loose-folder emotes and earlier packs (alphabetical) win id clashes; emotes.yml tuning sections
     * apply by id exactly like folder emotes (the gif/bubble/sound file-override keys do not — those
     * name files in the loose folders).
     */
    private void loadPacks(ConfigurationSection all, ConfigurationSection defaults, String defaultBubble,
            long maxBytes) {
        File[] zips = packsDir().listFiles((dir, n) -> n.toLowerCase(Locale.ROOT).endsWith(".zip"));
        if (zips == null || zips.length == 0) {
            return;
        }
        Arrays.sort(zips, Comparator.comparing(File::getName));
        for (File zip : zips) {
            try (ZipFile zf = new ZipFile(zip)) {
                loadPack(zip, zf, all, defaults, defaultBubble, maxBytes);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not read emote pack " + zip.getName() + ": " + e);
            }
        }
    }

    private void loadPack(File zip, ZipFile zf, ConfigurationSection all, ConfigurationSection defaults,
            String defaultBubble, long maxBytes) {
        // File name (lowercased) -> entry name, per category dir. An entry counts when it sits DIRECTLY
        // in a gifs/bubbles/sounds dir anywhere in the zip ("gifs/x.gif" or "MyPack/gifs/x.gif").
        Map<String, String> gifs = new HashMap<>();
        Map<String, String> bubbles = new HashMap<>();
        Map<String, String> sounds = new HashMap<>();
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
                case "gifs" -> gifs.putIfAbsent(fileName, entry.getName());
                case "bubbles" -> bubbles.putIfAbsent(fileName, entry.getName());
                case "sounds" -> sounds.putIfAbsent(fileName, entry.getName());
                default -> { }
            }
        }

        scanPackBubbles(zip, zf, bubbles, maxBytes); // every bubble in the pack is choosable

        // Image emotes, extensions in preference order (gif beats a same-named png) — as in the folders.
        Map<String, String> images = new TreeMap<>();
        for (String ext : new String[] {".gif", ".png", ".jpg", ".jpeg"}) {
            for (Map.Entry<String, String> file : gifs.entrySet()) {
                if (file.getKey().endsWith(ext)) {
                    String base = file.getKey().substring(0, file.getKey().length() - ext.length());
                    images.putIfAbsent(base.replaceAll("[^a-z0-9._-]", "_"), file.getValue());
                }
            }
        }

        int count = 0;
        for (Map.Entry<String, String> image : images.entrySet()) {
            String id = image.getKey();
            if (emotes.containsKey(id)) {
                continue; // loose-folder emotes and earlier packs win
            }
            ServerEmote e = packEmote(id, zip, zf, image.getValue(), bubbles, sounds, all, defaults,
                    defaultBubble, maxBytes);
            if (e != null && !e.gifHash.isEmpty()) {
                emotes.put(id, e);
                count++;
            }
        }

        // Every .ogg without a same-named image in this zip is a standalone sound-only emote.
        Map<String, String> soloSounds = new TreeMap<>();
        for (Map.Entry<String, String> file : sounds.entrySet()) {
            if (!file.getKey().endsWith(".ogg")) {
                continue;
            }
            String base = file.getKey().substring(0, file.getKey().length() - ".ogg".length());
            soloSounds.putIfAbsent(base.replaceAll("[^a-z0-9._-]", "_"), file.getValue());
        }
        for (Map.Entry<String, String> solo : soloSounds.entrySet()) {
            String id = solo.getKey();
            if (emotes.containsKey(id) || images.containsKey(id)) {
                continue;
            }
            ServerEmote e = packEmote(id, zip, zf, null, bubbles, sounds, all, defaults, defaultBubble,
                    maxBytes);
            if (e != null && !e.soundHash.isEmpty()) {
                emotes.put(id, e);
                count++;
            }
        }

        plugin.getLogger().info("Pack " + zip.getName() + ": " + count + " emotes");
    }

    /** One emote out of a pack zip; {@code imageEntry} is null for a sound-only emote. */
    private ServerEmote packEmote(String id, File zip, ZipFile zf, String imageEntry,
            Map<String, String> bubbles, Map<String, String> sounds, ConfigurationSection all,
            ConfigurationSection defaults, String defaultBubble, long maxBytes) {
        ServerEmote e = new ServerEmote(id);
        e.pack = zip.getName();

        if (imageEntry != null) {
            Hashed gif = hashZipEntry(zip, zf, imageEntry, maxBytes);
            if (gif == null) {
                return null;
            }
            e.gifHash = gif.hash();
            e.gifSize = gif.size();
        }

        // An explicit per-emote bubble inside the pack wins; otherwise the config default-bubble is
        // looked up INSIDE the pack (its name, e.g. default.png). Empty default-bubble, or a name the
        // pack doesn't contain, leaves bubbleHash "" — i.e. the emote shows with no speech bubble.
        String bubbleEntry = bubbles.get(id + ".png");
        if (bubbleEntry == null && defaultBubble != null && !defaultBubble.isEmpty()) {
            bubbleEntry = bubbles.get(defaultBubble.toLowerCase(Locale.ROOT));
        }
        if (bubbleEntry != null) {
            Hashed bubble = hashZipEntry(zip, zf, bubbleEntry, maxBytes);
            if (bubble != null) {
                e.bubbleHash = bubble.hash();
                e.bubbleSize = bubble.size();
            }
        }

        String soundEntry = sounds.get(id + ".ogg");
        if (soundEntry != null) {
            Hashed sound = hashZipEntry(zip, zf, soundEntry, maxBytes);
            if (sound != null) {
                e.soundHash = sound.hash();
                e.soundSize = sound.size();
            }
        }

        applyTuning(e, defaults);
        ConfigurationSection cfg = all == null ? null : all.getConfigurationSection(id);
        applyTuning(e, cfg);
        if (cfg != null && cfg.isSet("name")) {
            e.name = cfg.getString("name", id);
        }
        return e;
    }

    /** SHA-1 + size of a zip entry, registered in the hash index; null (+warn) when oversized/unreadable. */
    private Hashed hashZipEntry(File zip, ZipFile zf, String entryName, long maxBytes) {
        ZipEntry entry = zf.getEntry(entryName);
        if (entry == null) {
            return null;
        }
        if (entry.getSize() > maxBytes) {
            plugin.getLogger().warning("Pack asset too big (" + (entry.getSize() / 1024 / 1024)
                    + " MB > max-asset-mb): " + zip.getName() + "!" + entryName);
            return null;
        }
        try (InputStream in = zf.getInputStream(entry)) {
            byte[] data = in.readNBytes((int) maxBytes + 1);
            if (data.length > maxBytes) {
                plugin.getLogger().warning("Pack asset too big (> max-asset-mb): "
                        + zip.getName() + "!" + entryName);
                return null;
            }
            String hash = sha1(data);
            assetsByHash.put(hash, new Asset(zip, entryName));
            return new Hashed(hash, data.length);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to read pack asset " + zip.getName() + "!" + entryName
                    + ": " + ex);
            return null;
        }
    }

    /**
     * One emote from its scanned files + emotes.yml section; {@code imageFile} is null for a sound-only
     * emote. Null when the assets are missing/oversized (warned already).
     */
    private ServerEmote buildEmote(String id, File imageFile, File soloSound, ConfigurationSection all,
            ConfigurationSection defaults, String defaultBubble, long maxBytes) {
        ServerEmote e = new ServerEmote(id);
        ConfigurationSection cfg = all == null ? null : all.getConfigurationSection(id);

        e.gifFile = cfg != null && cfg.isString("gif")
                ? new File(gifsDir(), cfg.getString("gif")) : imageFile;

        String bubbleName = cfg != null && cfg.isSet("bubble")
                ? cfg.getString("bubble", "")
                : preferExisting(new File(bubblesDir(), id + ".png"), defaultBubble);
        if (bubbleName != null && !bubbleName.isEmpty()) {
            e.bubbleFile = new File(bubblesDir(), bubbleName);
        }

        String soundName = cfg != null && cfg.isSet("sound")
                ? cfg.getString("sound", "")
                : (soloSound != null ? soloSound.getName()
                        : (new File(soundsDir(), id + ".ogg").exists() ? id + ".ogg" : ""));
        if (soundName != null && !soundName.isEmpty()) {
            e.soundFile = new File(soundsDir(), soundName);
        }

        applyTuning(e, defaults);          // server-wide defaults first...
        applyTuning(e, cfg);               // ...then the emote's own keys override them
        if (cfg != null && cfg.isSet("name")) {
            e.name = cfg.getString("name", id);
        }

        return hashAssets(e, maxBytes) ? e : null;
    }

    /**
     * Overlay one tuning section onto the emote. Only keys actually PRESENT in the section are applied,
     * so calling this with {@code defaults} and then the emote's own section gives exact
     * "per-emote overrides defaults overrides hardcoded" layering. Setting {@code price} without an
     * explicit {@code default-access} re-derives access from the new price (paid => locked).
     */
    private static void applyTuning(ServerEmote e, ConfigurationSection cfg) {
        if (cfg == null) {
            return;
        }
        if (cfg.isSet("price")) {
            e.price = cfg.getDouble("price");
            e.defaultAccess = e.price <= 0.0;
        }
        if (cfg.isSet("default-access")) {
            e.defaultAccess = cfg.getBoolean("default-access");
        }
        if (cfg.isSet("scale")) {
            e.scale = (float) cfg.getDouble("scale");
        }
        if (cfg.isSet("anchor")) {
            e.anchor = (float) cfg.getDouble("anchor");
        }
        if (cfg.isSet("offset.x")) {
            e.offsetX = (float) cfg.getDouble("offset.x");
        }
        if (cfg.isSet("offset.y")) {
            e.offsetY = (float) cfg.getDouble("offset.y");
        }
        if (cfg.isSet("offset.z")) {
            e.offsetZ = (float) cfg.getDouble("offset.z");
        }
        if (cfg.isSet("gif-scale")) {
            e.gifScale = (float) cfg.getDouble("gif-scale");
        }
        if (cfg.isSet("gif-offset.x")) {
            e.gifOffsetX = (float) cfg.getDouble("gif-offset.x");
        }
        if (cfg.isSet("gif-offset.y")) {
            e.gifOffsetY = (float) cfg.getDouble("gif-offset.y");
        }
        if (cfg.isSet("sound-volume")) {
            e.soundVolume = (float) cfg.getDouble("sound-volume");
        }
        if (cfg.isSet("sound-pitch")) {
            e.soundPitch = (float) cfg.getDouble("sound-pitch");
        }
        if (cfg.isSet("sound-range")) {
            e.soundRange = cfg.getInt("sound-range");
        }
        if (cfg.isSet("loops")) {
            e.loops = (float) cfg.getDouble("loops");
        }
    }

    /**
     * Computes the content hashes; false when the emote has no usable primary asset — an image emote
     * needs its image, a sound-only emote its sound.
     */
    private boolean hashAssets(ServerEmote e, long maxBytes) {
        if (e.gifFile != null) {
            String gifHash = hashFile(e.gifFile, maxBytes);
            if (gifHash == null) {
                return false;
            }
            e.gifHash = gifHash;
            e.gifSize = (int) e.gifFile.length();
        }

        if (e.bubbleFile != null) {
            String h = hashFile(e.bubbleFile, maxBytes);
            if (h != null) {
                e.bubbleHash = h;
                e.bubbleSize = (int) e.bubbleFile.length();
            } else {
                e.bubbleFile = null;
            }
        }
        if (e.soundFile != null) {
            String h = hashFile(e.soundFile, maxBytes);
            if (h != null) {
                e.soundHash = h;
                e.soundSize = (int) e.soundFile.length();
            } else {
                e.soundFile = null;
            }
        }
        return !e.gifHash.isEmpty() || !e.soundHash.isEmpty();
    }

    /** SHA-1 hex of the file, registering it in the hash index; null (+warn) when missing/oversized. */
    private String hashFile(File file, long maxBytes) {
        if (file == null || !file.isFile()) {
            plugin.getLogger().warning("Emote asset missing: " + file);
            return null;
        }
        if (file.length() > maxBytes) {
            plugin.getLogger().warning("Emote asset too big (" + (file.length() / 1024 / 1024)
                    + " MB > max-asset-mb): " + file.getName());
            return null;
        }
        try {
            String hash = sha1(Files.readAllBytes(file.toPath()));
            assetsByHash.put(hash, new Asset(file, null));
            return hash;
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to hash emote asset " + file.getName() + ": " + ex);
            return null;
        }
    }

    private static String sha1(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-1 is mandatory in every JRE
        }
    }

    private static String preferExisting(File specific, String fallback) {
        return specific.isFile() ? specific.getName() : fallback;
    }

    public ServerEmote get(String id) {
        return id == null ? null : emotes.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<ServerEmote> all() {
        return emotes.values();
    }

    public Asset assetByHash(String hash) {
        return assetsByHash.get(hash);
    }

    /** Persist a price change into emotes.yml (keeps the emote's other keys). */
    public void setPrice(String id, double price) {
        ServerEmote e = get(id);
        if (e == null) {
            return;
        }
        e.price = price;
        saveKey("emotes." + e.id + ".price", price);
    }

    /** Every parameter tunable via {@code /memes set} (display label -> yml path handled in setValue). */
    public static final List<String> TUNABLE_PARAMS = List.of(
            "name", "price", "default-access", "scale", "anchor", "offset-x", "offset-y", "offset-z",
            "gif-scale", "gif-offset-x", "gif-offset-y", "sound-volume", "sound-pitch", "sound-range",
            "loops");

    /**
     * Apply one {@code /memes set} parameter: updates the live emote AND persists it into emotes.yml.
     *
     * @return null on success, otherwise a human-readable error
     */
    public String setValue(ServerEmote e, String param, String value) {
        return apply(e, param, value, "emotes." + e.id + ".");
    }

    /**
     * Set a server-wide default ({@code /memes set defaults <param> <value>} → the {@code defaults:}
     * section of emotes.yml). The caller must {@code reloadAll()} afterwards — defaults are baked into
     * every emote at load time, not resolved per request.
     *
     * @return null on success, otherwise a human-readable error
     */
    public String setDefault(String param, String value) {
        if ("name".equalsIgnoreCase(param)) {
            return "name нельзя задавать по умолчанию — только у конкретной эмоции.";
        }
        return apply(new ServerEmote("__defaults__"), param, value, "defaults.");
    }

    /** The shared parse/clamp/persist switch behind setValue/setDefault. */
    private String apply(ServerEmote e, String param, String value, String prefix) {
        try {
            switch (param.toLowerCase(Locale.ROOT)) {
                case "name" -> {
                    e.name = value;
                    saveKey(prefix + "name", value);
                }
                case "price" -> {
                    e.price = Double.parseDouble(value);
                    saveKey(prefix + "price", e.price);
                }
                case "default-access" -> {
                    e.defaultAccess = Boolean.parseBoolean(value);
                    saveKey(prefix + "default-access", e.defaultAccess);
                }
                case "scale" -> {
                    e.scale = parseFloat(value, 0.05f, 20.0f);
                    saveKey(prefix + "scale", e.scale);
                }
                case "anchor" -> {
                    e.anchor = parseFloat(value, -2.0f, 8.0f);
                    saveKey(prefix + "anchor", e.anchor);
                }
                case "offset-x" -> {
                    e.offsetX = parseFloat(value, -32.0f, 32.0f);
                    saveKey(prefix + "offset.x", e.offsetX);
                }
                case "offset-y" -> {
                    e.offsetY = parseFloat(value, -32.0f, 32.0f);
                    saveKey(prefix + "offset.y", e.offsetY);
                }
                case "offset-z" -> {
                    e.offsetZ = parseFloat(value, -32.0f, 32.0f);
                    saveKey(prefix + "offset.z", e.offsetZ);
                }
                case "gif-scale" -> {
                    e.gifScale = parseFloat(value, 0.05f, 5.0f);
                    saveKey(prefix + "gif-scale", e.gifScale);
                }
                case "gif-offset-x" -> {
                    e.gifOffsetX = parseFloat(value, -2048.0f, 2048.0f);
                    saveKey(prefix + "gif-offset.x", e.gifOffsetX);
                }
                case "gif-offset-y" -> {
                    e.gifOffsetY = parseFloat(value, -2048.0f, 2048.0f);
                    saveKey(prefix + "gif-offset.y", e.gifOffsetY);
                }
                case "sound-volume" -> {
                    e.soundVolume = parseFloat(value, 0.0f, 4.0f);
                    saveKey(prefix + "sound-volume", e.soundVolume);
                }
                case "sound-pitch" -> {
                    e.soundPitch = parseFloat(value, 0.1f, 4.0f);
                    saveKey(prefix + "sound-pitch", e.soundPitch);
                }
                case "sound-range" -> {
                    e.soundRange = Math.max(1, Math.min(256, Integer.parseInt(value)));
                    saveKey(prefix + "sound-range", e.soundRange);
                }
                case "loops" -> {
                    e.loops = parseFloat(value, 0.0f, 100.0f);
                    saveKey(prefix + "loops", e.loops);
                }
                default -> {
                    return "Неизвестный параметр: " + param + ". Доступны: "
                            + String.join(", ", TUNABLE_PARAMS);
                }
            }
        } catch (NumberFormatException ex) {
            return "Неверное число: " + value;
        }
        return null;
    }

    private static float parseFloat(String value, float min, float max) {
        return Math.max(min, Math.min(max, Float.parseFloat(value)));
    }

    private void saveKey(String path, Object value) {
        emotesYml.set(path, value);
        try {
            emotesYml.save(emotesYmlFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save emotes.yml: " + ex);
        }
    }
}
