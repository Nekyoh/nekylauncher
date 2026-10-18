package com.nekyoh.nekylauncher.auth;

import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.util.CancelToken;
import com.nekyoh.nekylauncher.util.Http;
import com.nekyoh.nekylauncher.util.JArray;
import com.nekyoh.nekylauncher.util.JObject;
import com.nekyoh.nekylauncher.util.Log;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Microsoft account sign-in using the OAuth 2.0 device code flow, then Xbox Live, XSTS and
 * Minecraft services, as documented for third-party launchers.
 *
 * <p>Requires an Azure application (client) id with the "XboxLive.signin" scope that Mojang has
 * approved for the Minecraft API. Configure it in Settings.</p>
 */
public final class MicrosoftAuth {
    private static final String AUTHORITY = "https://login.microsoftonline.com/consumers/oauth2/v2.0";
    private static final String SCOPE = "XboxLive.signin offline_access";

    public record DeviceCode(String userCode, String deviceCode, String verificationUri, int intervalSec, long expiresAt) {}

    private final String clientId;

    public MicrosoftAuth(String clientId) {
        if (clientId == null || clientId.isBlank()) throw new IllegalStateException(I18n.tr("ms.noClientId"));
        this.clientId = clientId.trim();
    }

    public DeviceCode startDeviceCode() throws IOException {
        Http.Response r = Http.postForm(AUTHORITY + "/devicecode", Map.of("client_id", clientId, "scope", SCOPE));
        JObject j = r.json();
        if (r.status() != 200) throw new IOException(j.str("error_description", "Device code request failed (" + r.status() + ")"));
        return new DeviceCode(j.str("user_code"), j.str("device_code"),
                j.str("verification_uri", "https://www.microsoft.com/link"),
                j.integer("interval", 5), System.currentTimeMillis() + j.lng("expires_in", 900) * 1000);
    }

    /** Polls until the user finishes signing in, then completes the whole chain. */
    public Account pollAndLogin(DeviceCode dc, CancelToken cancel) throws IOException {
        int interval = Math.max(1, dc.intervalSec());
        while (System.currentTimeMillis() < dc.expiresAt()) {
            sleep(interval * 1000L, cancel);
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            form.put("client_id", clientId);
            form.put("device_code", dc.deviceCode());
            Http.Response r = Http.postForm(AUTHORITY + "/token", form);
            JObject j = r.json();
            if (r.status() == 200) {
                Account a = new Account();
                a.id = UUID.randomUUID().toString();
                a.type = Account.Type.MICROSOFT;
                completeLogin(a, j.str("access_token"), j.str("refresh_token"));
                return a;
            }
            String err = j.str("error", "");
            switch (err) {
                case "authorization_pending" -> { }
                case "slow_down" -> interval += 5;
                case "authorization_declined" -> throw new IOException(I18n.tr("ms.declined"));
                case "expired_token" -> throw new IOException(I18n.tr("ms.expired"));
                default -> throw new IOException(j.str("error_description", err));
            }
        }
        throw new IOException(I18n.tr("ms.expired"));
    }

    /**
     * One-click sign-in like MultiMC/Prism: opens Microsoft's login page in the browser (OAuth 2.0
     * authorization code + PKCE) and receives the result on a temporary local web server
     * (http://localhost:PORT). The Azure app needs the redirect URI "http://localhost" under
     * "Mobile and desktop applications".
     *
     * @param openBrowser receives the login URL to open (and to show if the browser did not open)
     */
    public Account loginWithBrowser(java.util.function.Consumer<String> openBrowser, CancelToken cancel) throws IOException {
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        byte[] v = new byte[48];
        rnd.nextBytes(v);
        java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
        String verifier = b64.encodeToString(v);
        String challenge = b64.encodeToString(com.nekyoh.nekylauncher.util.Hashes.digest("SHA-256")
                .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        byte[] st = new byte[16];
        rnd.nextBytes(st);
        String state = b64.encodeToString(st);

        java.util.concurrent.CompletableFuture<Map<String, String>> result = new java.util.concurrent.CompletableFuture<>();
        com.sun.net.httpserver.HttpHandler handler = ex -> {
            try (ex) {
                if (!"/".equals(ex.getRequestURI().getPath())) { // favicon etc.
                    ex.sendResponseHeaders(404, -1);
                    return;
                }
                Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
                boolean ok = q.containsKey("code");
                byte[] page = resultPage(ok).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(200, page.length);
                ex.getResponseBody().write(page);
                if (ok || q.containsKey("error")) result.complete(q);
            }
        };
        java.net.InetAddress v4 = java.net.InetAddress.getByName("127.0.0.1");
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(v4, 0), 0);
        server.createContext("/", handler);
        server.start();
        int port = server.getAddress().getPort();
        // browsers may resolve "localhost" to ::1 first: listen there too when possible
        com.sun.net.httpserver.HttpServer server6 = null;
        try {
            server6 = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getByName("::1"), port), 0);
            server6.createContext("/", handler);
            server6.start();
        } catch (IOException | RuntimeException ignored) {
            server6 = null;
        }
        String redirect = "http://localhost:" + port;
        try {
            Map<String, String> auth = new LinkedHashMap<>();
            auth.put("client_id", clientId);
            auth.put("response_type", "code");
            auth.put("redirect_uri", redirect);
            auth.put("response_mode", "query");
            auth.put("scope", SCOPE);
            auth.put("code_challenge", challenge);
            auth.put("code_challenge_method", "S256");
            auth.put("state", state);
            auth.put("prompt", "select_account");
            openBrowser.accept(AUTHORITY + "/authorize?" + Http.formEncode(auth));

            Map<String, String> q = null;
            long deadline = System.currentTimeMillis() + 10 * 60_000L;
            while (q == null) {
                if (cancel != null) cancel.check();
                if (System.currentTimeMillis() > deadline) throw new IOException(I18n.tr("ms.expired"));
                try {
                    q = result.get(300, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException ignored) {
                    // keep waiting
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancelToken.CancelledException();
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IOException(e.getCause());
                }
            }
            if (q.containsKey("error")) {
                String err = q.get("error");
                if ("access_denied".equals(err)) throw new IOException(I18n.tr("ms.declined"));
                throw new IOException(q.getOrDefault("error_description", err));
            }
            if (!state.equals(q.get("state"))) throw new IOException("Invalid sign-in response (state mismatch)");

            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "authorization_code");
            form.put("client_id", clientId);
            form.put("code", q.get("code"));
            form.put("redirect_uri", redirect);
            form.put("code_verifier", verifier);
            form.put("scope", SCOPE);
            Http.Response r = Http.postForm(AUTHORITY + "/token", form);
            JObject j = r.json();
            if (r.status() != 200) throw new IOException(j.str("error_description", "Token request failed (" + r.status() + ")"));
            Account a = new Account();
            a.id = UUID.randomUUID().toString();
            a.type = Account.Type.MICROSOFT;
            completeLogin(a, j.str("access_token"), j.str("refresh_token"));
            return a;
        } finally {
            server.stop(0);
            if (server6 != null) server6.stop(0);
        }
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            m.put(java.net.URLDecoder.decode(k, java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8));
        }
        return m;
    }

    /** Static page shown in the browser after Microsoft redirects back (plain HTML + CSS). */
    static String resultPage(boolean ok) {
        String title = ok ? I18n.tr("ms.browserDoneTitle") : I18n.tr("ms.browserFailTitle");
        String text = ok ? I18n.tr("ms.browserDoneText") : I18n.tr("ms.browserFailText");
        String color = ok ? "#22c55e" : "#f43f5e";
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>NekyLauncher</title><style>"
                + "body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;background:#0b0d12;"
                + "font-family:Segoe UI,Inter,system-ui,sans-serif;color:#e9ecf1}"
                + ".c{background:#141821;border:1px solid #313847;border-radius:18px;padding:40px 48px;text-align:center;max-width:420px}"
                + "h1{font-size:22px;margin:16px 0 8px}p{color:#a0a8b7;line-height:1.5;margin:0}"
                + ".b{width:56px;height:56px;border-radius:50%;margin:auto;background:" + color + ";opacity:.9}"
                + ".n{margin-top:22px;font-size:12px;color:#6a7384}</style></head><body><div class=\"c\"><div class=\"b\"></div>"
                + "<h1>" + escape(title) + "</h1><p>" + escape(text) + "</p><div class=\"n\">NekyLauncher by Nekyoh</div></div></body></html>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Refreshes the Minecraft token using the stored Microsoft refresh token. */
    public void refresh(Account a) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("client_id", clientId);
        form.put("refresh_token", a.refreshToken);
        form.put("scope", SCOPE);
        Http.Response r = Http.postForm(AUTHORITY + "/token", form);
        JObject j = r.json();
        if (r.status() != 200) throw new IOException(I18n.tr("ms.refreshFailed") + " (" + j.str("error", "" + r.status()) + ")");
        completeLogin(a, j.str("access_token"), j.str("refresh_token", a.refreshToken));
    }

    private void completeLogin(Account a, String msAccessToken, String msRefreshToken) throws IOException {
        // Xbox Live
        JObject xblReq = new JObject()
                .put("Properties", new JObject().put("AuthMethod", "RPS").put("SiteName", "user.auth.xboxlive.com").put("RpsTicket", "d=" + msAccessToken))
                .put("RelyingParty", "http://auth.xboxlive.com")
                .put("TokenType", "JWT");
        Http.Response xbl = Http.postJson("https://user.auth.xboxlive.com/user/authenticate", xblReq);
        if (xbl.status() != 200) throw new IOException("Xbox Live authentication failed (" + xbl.status() + ")");
        JObject xblJ = xbl.json();
        String xblToken = xblJ.str("Token");
        String uhs = firstXui(xblJ).str("uhs");

        // XSTS for Minecraft services
        JObject xstsReq = new JObject()
                .put("Properties", new JObject().put("SandboxId", "RETAIL").put("UserTokens", new JArray().add(xblToken)))
                .put("RelyingParty", "rp://api.minecraftservices.com/")
                .put("TokenType", "JWT");
        Http.Response xsts = Http.postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsReq);
        if (xsts.status() == 401) {
            long xerr = 0;
            try { xerr = xsts.json().lng("XErr", 0); } catch (Exception ignored) { }
            throw new IOException(switch ((int) (xerr - 2148916200L)) {
                case 33 -> I18n.tr("ms.noXbox");
                case 35 -> I18n.tr("ms.xboxBanned");
                case 36, 37 -> I18n.tr("ms.adultVerify");
                case 38 -> I18n.tr("ms.child");
                default -> "XSTS authorization failed (" + xerr + ")";
            });
        }
        if (xsts.status() != 200) throw new IOException("XSTS authorization failed (" + xsts.status() + ")");
        JObject xstsJ = xsts.json();
        String xstsToken = xstsJ.str("Token");
        String xstsUhs = firstXui(xstsJ).str("uhs", uhs);

        // Minecraft services
        Http.Response mc = Http.postJson("https://api.minecraftservices.com/authentication/login_with_xbox",
                new JObject().put("identityToken", "XBL3.0 x=" + xstsUhs + ";" + xstsToken));
        if (mc.status() == 403) throw new IOException(I18n.tr("ms.appNotApproved"));
        if (mc.status() != 200) throw new IOException("Minecraft login failed (" + mc.status() + ")");
        JObject mcJ = mc.json();
        String mcToken = mcJ.str("access_token");
        long expires = mcJ.lng("expires_in", 86400);

        Http.Response prof = Http.getWithAuth("https://api.minecraftservices.com/minecraft/profile", mcToken);
        if (prof.status() == 404) throw new IOException(I18n.tr("ms.noGame"));
        if (prof.status() != 200) throw new IOException("Could not load Minecraft profile (" + prof.status() + ")");
        JObject p = prof.json();

        a.username = p.str("name");
        a.uuid = p.str("id");
        a.accessToken = mcToken;
        a.refreshToken = msRefreshToken;
        a.expiresAt = System.currentTimeMillis() + expires * 1000;
        a.xuid = xuidFrom(xblToken);
        a.skinUrl = "";
        for (JObject skin : p.arrOrEmpty("skins").objects()) {
            if ("ACTIVE".equalsIgnoreCase(skin.str("state", "ACTIVE"))) {
                a.skinUrl = skin.str("url", "");
                break;
            }
        }
        Log.info("Signed in to Microsoft account " + a.username);
    }

    private static JObject firstXui(JObject resp) {
        JArray xui = resp.objOrEmpty("DisplayClaims").arr("xui");
        return xui != null && xui.size() > 0 && xui.obj(0) != null ? xui.obj(0) : new JObject();
    }

    /** The XUID is optional for launching; we leave it empty rather than making an extra request. */
    private static String xuidFrom(String ignored) { return ""; }

    private static void sleep(long ms, CancelToken cancel) throws IOException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cancel != null) cancel.check();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelToken.CancelledException();
            }
        }
    }
}
