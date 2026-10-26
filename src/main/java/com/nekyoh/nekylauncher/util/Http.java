package com.nekyoh.nekylauncher.util;

import com.nekyoh.nekylauncher.AppInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.LongConsumer;

/** Thin wrapper over java.net.http with sane defaults, retries and verified downloads. */
public final class Http {
    private Http() {}

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public static final class HttpException extends IOException {
        public final int status;
        public final String body;

        public HttpException(String url, int status, String body) {
            super("HTTP " + status + " for " + url);
            this.status = status;
            this.body = body;
        }
    }

    public record Response(int status, String body) {
        public JObject json() { return JObject.parse(body); }
    }

    private static HttpRequest.Builder req(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", AppInfo.USER_AGENT);
    }

    private static <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> h) throws IOException {
        try {
            return CLIENT.send(r, h);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted");
        }
    }

    public static String getString(String url) throws IOException { return getString(url, Map.of()); }

    public static String getString(String url, Map<String, String> headers) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest.Builder b = req(url).GET();
                headers.forEach(b::header);
                HttpResponse<String> r = send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (r.statusCode() / 100 == 2) return r.body();
                HttpException he = new HttpException(url, r.statusCode(), r.body());
                if (r.statusCode() < 500 && r.statusCode() != 429) throw he;
                last = he;
            } catch (HttpException e) {
                if (e.status < 500 && e.status != 429) throw e;
                last = e;
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException e) {
                last = e;
            }
            sleep(600L * (attempt + 1));
        }
        throw last;
    }

    public static JObject getJson(String url) throws IOException { return JObject.parse(getString(url)); }

    public static JObject getJson(String url, Map<String, String> headers) throws IOException {
        return JObject.parse(getString(url, headers));
    }

    public static JArray getJsonArray(String url) throws IOException { return JArray.parse(getString(url)); }

    public static byte[] getBytes(String url) throws IOException {
        HttpResponse<byte[]> r = send(req(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() / 100 != 2) throw new HttpException(url, r.statusCode(), "");
        return r.body();
    }

    public static Response post(String url, String contentType, String body, Map<String, String> headers) throws IOException {
        HttpRequest.Builder b = req(url).POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", contentType)
                .header("Accept", "application/json");
        headers.forEach(b::header);
        HttpResponse<String> r = send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(r.statusCode(), r.body());
    }

    public static Response postJson(String url, JObject body) throws IOException {
        return post(url, "application/json", body.toJson(false), Map.of());
    }

    public static Response postForm(String url, Map<String, String> form) throws IOException {
        return post(url, "application/x-www-form-urlencoded", formEncode(form), Map.of());
    }

    public static Response getWithAuth(String url, String bearer) throws IOException {
        HttpResponse<String> r = send(req(url).GET().header("Authorization", "Bearer " + bearer).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(r.statusCode(), r.body());
    }

    /** Generic authenticated request (PUT/DELETE/POST...) with an optional body. */
    public static Response request(String method, String url, Map<String, String> headers, String contentType, byte[] body) throws IOException {
        HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body);
        HttpRequest.Builder b = req(url).method(method, pub).header("Accept", "application/json");
        if (contentType != null) b.header("Content-Type", contentType);
        headers.forEach(b::header);
        HttpResponse<String> r = send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(r.statusCode(), r.body());
    }

    /** multipart/form-data with text fields and one file part. */
    public static Response postMultipart(String url, Map<String, String> headers, Map<String, String> fields,
                                         String fileField, String fileName, String fileType, byte[] file) throws IOException {
        String boundary = "----NekyBoundary" + Long.toHexString(System.nanoTime());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (var f : fields.entrySet()) {
            out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n"
                    + f.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField + "\"; filename=\"" + fileName
                + "\"\r\nContent-Type: " + fileType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(file);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return request("POST", url, headers, "multipart/form-data; boundary=" + boundary, out.toByteArray());
    }

    public static String formEncode(Map<String, String> form) {
        StringJoiner j = new StringJoiner("&");
        form.forEach((k, v) -> j.add(enc(k) + "=" + enc(v)));
        return j.toString();
    }

    public static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /**
     * Downloads to {@code target} atomically, verifying sha1 when given. Retries up to 3 times.
     * {@code onBytes} receives byte deltas (may be negative to roll back a failed attempt).
     */
    public static void download(String url, Path target, String sha1, LongConsumer onBytes, CancelToken cancel) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            long received = 0;
            try {
                if (cancel != null) cancel.check();
                HttpResponse<InputStream> r = send(req(url).timeout(Duration.ofMinutes(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (r.statusCode() / 100 != 2) {
                    r.body().close();
                    HttpException he = new HttpException(url, r.statusCode(), "");
                    if (r.statusCode() == 404 || r.statusCode() == 403 || r.statusCode() == 410) throw he;
                    throw (IOException) he;
                }
                MessageDigest md = sha1 != null ? Hashes.digest("SHA-1") : null;
                try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(part)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancel != null && cancel.isCancelled()) throw new CancelToken.CancelledException();
                        out.write(buf, 0, n);
                        if (md != null) md.update(buf, 0, n);
                        received += n;
                        if (onBytes != null) onBytes.accept(n);
                    }
                }
                if (md != null) {
                    String got = Hashes.hex(md.digest());
                    if (!got.equalsIgnoreCase(sha1)) throw new IOException("Checksum mismatch for " + url + " (expected " + sha1 + ", got " + got + ")");
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (CancelToken.CancelledException | InterruptedIOException e) {
                safeDelete(part);
                throw e;
            } catch (HttpException e) {
                safeDelete(part);
                if (onBytes != null && received > 0) onBytes.accept(-received);
                if (e.status == 404 || e.status == 403 || e.status == 410) throw e;
                last = e;
            } catch (IOException e) {
                safeDelete(part);
                if (onBytes != null && received > 0) onBytes.accept(-received);
                last = e;
            }
            sleep(800L * (attempt + 1));
        }
        throw last;
    }

    private static void safeDelete(Path p) {
        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
