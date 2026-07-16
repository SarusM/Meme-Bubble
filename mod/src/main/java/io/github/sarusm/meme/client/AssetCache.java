package io.github.sarusm.meme.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Content-addressed disk cache for server emote assets: {@code <gameDir>/meme/cache/<sha1>.bin}.
 * Because files are keyed by their SHA-1, the cache is shared across servers and never invalidates —
 * a changed file on the server simply has a new hash and downloads once.
 */
public final class AssetCache {
    private AssetCache() {
    }

    public static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("meme").resolve("cache");
    }

    public static Path path(String hash) {
        return dir().resolve(hash + ".bin");
    }

    public static boolean has(String hash) {
        return !hash.isEmpty() && Files.isRegularFile(path(hash));
    }

    /** Verifies the bytes against the claimed hash before persisting; false on mismatch (corrupt transfer). */
    public static boolean put(String hash, byte[] data) {
        if (!sha1(data).equals(hash)) {
            MemeClient.LOGGER.warn("Asset hash mismatch for {} — discarded", hash);
            return false;
        }
        try {
            Files.createDirectories(dir());
            Path tmp = dir().resolve(hash + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, path(hash), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            MemeClient.LOGGER.error("Could not write asset cache file for {}", hash, e);
            return false;
        }
    }

    public static String sha1(byte[] data) {
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
}
