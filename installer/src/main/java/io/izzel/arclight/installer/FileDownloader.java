package io.izzel.arclight.installer;

import javax.net.ssl.SSLException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.rmi.RemoteException;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Supplier;

public record FileDownloader(String url, String target, String hash) implements Supplier<Path> {

    // Timeouts configurable via system properties
    private static final int CONNECT_TIMEOUT_MS = Integer.getInteger(
        "arclight.download.connectTimeout", 20_000
    );
    private static final int READ_TIMEOUT_MS = Integer.getInteger(
        "arclight.download.readTimeout", 30_000
    );

    // Max redirects to follow before giving up
    private static final int MAX_REDIRECTS = 10;

    // User-agent so servers don't block us as an unknown bot
    private static final String USER_AGENT =
        "Arclight-Installer/1.0 (https://github.com/IzzelAliz/Arclight)";

    @Override
    public Path get() {
        try {
            Path path = new File(target).toPath();

            // Remove stale directory at target path if present
            if (Files.exists(path) && Files.isDirectory(path)) {
                Files.delete(path);
            }

            // File already exists — validate hash and return early
            if (Files.exists(path)) {
                if (hashMatches(path, this.hash)) {
                    return path;
                }
                // Hash mismatch — delete and re-download
                Files.delete(path);
            }

            // Ensure parent directories exist
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            return downloadToTemp(path);

        } catch (AccessDeniedException e) {
            throw new RuntimeException(
                "Access denied for file: " + e.getFile() +
                ". Check that the server directory is writable.", e
            );
        } catch (Exception e) {
            Util.throwException(e);
            return null;
        }
    }

    // Downloads to a .tmp file then atomically moves it to the final path
private Path downloadToTemp(Path finalPath) throws IOException {
    Path tmp = new File(target + ".tmp").toPath();

    System.out.println("  Downloading: " + url);

    try (InputStream stream = read(url)) {
        Files.copy(stream, tmp, StandardCopyOption.REPLACE_EXISTING);
    } catch (SocketTimeoutException e) {
        deleteSilently(tmp);
        throw new RuntimeException(
            "Timeout after " + READ_TIMEOUT_MS + "ms while downloading: " + url
        );
    } catch (SSLException e) {
        deleteSilently(tmp);
        throw new RuntimeException(
            "SSL error while downloading: " + url + " — " + e.getMessage()
        );
    } catch (Exception e) {
        deleteSilently(tmp);
        throw new IOException("Failed to download: " + url, e);
    }

    if (!Files.exists(tmp)) {
        throw new RuntimeException("Downloaded file not found after transfer: " + url);
    }

    // Validate hash of the downloaded file
    String actualHash;
    try {
        actualHash = Util.hash(tmp);
    } catch (Exception e) {
        deleteSilently(tmp);
        throw new IOException("Failed to compute hash for downloaded file: " + tmp, e);
    }

    if (!actualHash.equalsIgnoreCase(this.hash)) {
        deleteSilently(tmp);
        throw new RuntimeException(
            "Hash mismatch for: " + url +
            "\n  Expected : " + this.hash +
            "\n  Actual   : " + actualHash
        );
    }

    // Atomically replace the final file
    Files.move(tmp, finalPath, StandardCopyOption.REPLACE_EXISTING);
    return finalPath;
}

    // Checks whether an existing file matches the expected hash
private static boolean hashMatches(Path path, String expectedHash) {
    if (expectedHash == null || expectedHash.isBlank()) return true;
    try {
        return Util.hash(path).equalsIgnoreCase(expectedHash);
    } catch (Exception e) {
        return false;
    }
}

    // Deletes a file without throwing — used for cleanup of .tmp files
    private static void deleteSilently(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    // Public — used by MinecraftProvider for manifest downloads
    static InputStream read(String url) throws IOException {
        return redirect(new URL(url), new HashSet<>(), 0);
    }

    private static InputStream redirect(URL url, Set<String> history, int depth) throws IOException {
        if (depth > MAX_REDIRECTS) {
            StringJoiner joiner = new StringJoiner("\n  → ");
            joiner.add("Too many redirects (" + MAX_REDIRECTS + "):");
            history.forEach(joiner::add);
            throw new RuntimeException(joiner.toString());
        }

        if (history.contains(url.toString())) {
            throw new RuntimeException("Redirect loop detected at: " + url);
        }
        history.add(url.toString());

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept-Encoding", "identity");

        int responseCode = connection.getResponseCode();

        switch (responseCode) {
            case HttpURLConnection.HTTP_OK -> {
                return connection.getInputStream();
            }
            case HttpURLConnection.HTTP_MOVED_PERM,
                 HttpURLConnection.HTTP_MOVED_TEMP,
                 307, 308 -> {
                String location = URLDecoder.decode(
                    connection.getHeaderField("Location"),
                    StandardCharsets.UTF_8
                );
                connection.disconnect();
                return redirect(new URL(url, location), history, depth + 1);
            }
            case HttpURLConnection.HTTP_NOT_FOUND -> {
                connection.disconnect();
                throw new RuntimeException("Not found (404): " + url);
            }
            case HttpURLConnection.HTTP_FORBIDDEN -> {
                connection.disconnect();
                throw new RuntimeException("Forbidden (403): " + url);
            }
            default -> {
                connection.disconnect();
                throw new RemoteException("HTTP " + responseCode + ": " + url);
            }
        }
    }
}