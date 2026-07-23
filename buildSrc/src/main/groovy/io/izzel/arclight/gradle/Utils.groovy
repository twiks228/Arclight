package io.izzel.arclight.gradle

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.function.Consumer

class Utils {

    // Timeout values in milliseconds
    private static final int CONNECT_TIMEOUT = 30_000
    private static final int READ_TIMEOUT    = 60_000
    private static final int MAX_REDIRECTS   = 10
    private static final int MAX_RETRIES     = 3

    private static final String USER_AGENT =
        "Arclight-Build/1.0 (https://github.com/IzzelAliz/Arclight)"

    /**
     * Downloads a file from the given URL to the destination.
     * Supports redirects, retries and proper timeouts.
     * Throws a descriptive exception on failure.
     */
    static void download(String url, File dist) {
        download(url, dist, MAX_RETRIES)
    }

    static void download(String url, File dist, int retries) {
        dist.parentFile?.mkdirs()

        Exception lastError = null

        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                if (retries > 1) {
                    println "  Downloading (attempt $attempt/$retries): $url"
                } else {
                    println "  Downloading: $url"
                }

                downloadOnce(url, dist)
                println "  Downloaded: ${dist.name}"
                return

            } catch (Exception e) {
                lastError = e
                println "  Attempt $attempt failed: ${e.message}"

                if (attempt < retries) {
                    long delay = 1000L * attempt
                    println "  Retrying in ${delay}ms..."
                    Thread.sleep(delay)
                }
            }
        }

        throw new RuntimeException(
            "Failed to download '$url' after $retries attempt(s): ${lastError?.message}",
            lastError
        )
    }

    // Performs a single download attempt with redirect support
    private static void downloadOnce(String url, File dist) {
        File tmp = new File(dist.absolutePath + ".tmp")
        tmp.parentFile?.mkdirs()

        try {
            InputStream stream = openWithRedirects(new URL(url), 0)
            try {
                Files.copy(stream, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                stream.close()
            }

            Files.move(tmp.toPath(), dist.toPath(), StandardCopyOption.REPLACE_EXISTING)

        } catch (Exception e) {
            tmp.delete()
            throw e
        }
    }

    // Opens a URL with redirect handling
    private static InputStream openWithRedirects(URL url, int depth) {
        if (depth > MAX_REDIRECTS) {
            throw new RuntimeException("Too many redirects (>${MAX_REDIRECTS}) for: $url")
        }

        HttpURLConnection conn = (HttpURLConnection) url.openConnection()
        conn.instanceFollowRedirects = false
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout    = READ_TIMEOUT
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept-Encoding", "identity")

        int code = conn.responseCode

        switch (code) {
            case HttpURLConnection.HTTP_OK:
                return conn.inputStream

            case [HttpURLConnection.HTTP_MOVED_PERM,
                  HttpURLConnection.HTTP_MOVED_TEMP,
                  307, 308]:
                String location = conn.getHeaderField("Location")
                conn.disconnect()
                if (!location) {
                    throw new RuntimeException("Redirect with no Location header from: $url")
                }
                println "    Redirecting to: $location"
                return openWithRedirects(new URL(url, location), depth + 1)

            case HttpURLConnection.HTTP_NOT_FOUND:
                conn.disconnect()
                throw new RuntimeException("Not found (404): $url")

            case HttpURLConnection.HTTP_FORBIDDEN:
                conn.disconnect()
                throw new RuntimeException("Forbidden (403): $url")

            default:
                conn.disconnect()
                throw new RuntimeException("HTTP $code from: $url")
        }
    }

    static <T extends AutoCloseable> void using(T closeable, Consumer<T> consumer) {
        try {
            consumer.accept(closeable)
        } finally {
            closeable.close()
        }
    }

    static void write(InputStream i, OutputStream o) {
        byte[] buf = new byte[8192]
        int len
        while ((len = i.read(buf)) > 0) {
            o.write(buf, 0, len)
        }
    }

    static String sha1(File file) {
        MessageDigest md = MessageDigest.getInstance("SHA-1")
        file.eachByte(8192) { bytes, size ->
            md.update(bytes, 0 as byte, size)
        }
        return md.digest().collect { String.format("%02x", it) }.join()
    }
}