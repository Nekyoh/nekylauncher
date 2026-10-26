package com.nekyoh.nekylauncher.skins;

import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.util.Http;
import com.nekyoh.nekylauncher.util.JObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minecraft Services profile API: the same calls the official launcher uses to change the skin
 * (upload with classic/slim model) and to pick which owned cape is shown.
 */
public final class SkinApi {
    private SkinApi() {}

    private static volatile String PROFILE = "https://api.minecraftservices.com/minecraft/profile";

    /** Tests only: point the client at a local server. */
    public static void setProfileUrlForTests(String url) { PROFILE = url; }

    public record Skin(String id, String state, String url, boolean slim) {}

    public record Cape(String id, String state, String url, String alias) {
        public boolean active() { return "ACTIVE".equalsIgnoreCase(state); }
    }

    public record Profile(String id, String name, List<Skin> skins, List<Cape> capes) {
        public Skin activeSkin() {
            for (Skin s : skins) if ("ACTIVE".equalsIgnoreCase(s.state())) return s;
            return skins.isEmpty() ? null : skins.get(0);
        }

        public Cape activeCape() {
            for (Cape c : capes) if (c.active()) return c;
            return null;
        }
    }

    public static Profile fetch(String token) throws IOException {
        return check(Http.getWithAuth(PROFILE, token), token);
    }

    /** Uploads a PNG skin (64×64 or legacy 64×32) with the chosen arm model. */
    public static Profile uploadSkin(String token, byte[] png, boolean slim) throws IOException {
        Http.Response r = Http.postMultipart(PROFILE + "/skins", auth(token), Map.of("variant", slim ? "slim" : "classic"),
                "file", "skin.png", "image/png", png);
        return check(r, token);
    }

    /** Shows one of the account's capes, or hides the cape when {@code capeId} is null/blank. */
    public static Profile setCape(String token, String capeId) throws IOException {
        Http.Response r = capeId == null || capeId.isBlank()
                ? Http.request("DELETE", PROFILE + "/capes/active", auth(token), null, null)
                : Http.request("PUT", PROFILE + "/capes/active", auth(token), "application/json",
                new JObject().put("capeId", capeId).toJson(false).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return check(r, token);
    }

    private static Map<String, String> auth(String token) { return Map.of("Authorization", "Bearer " + token); }

    private static Profile check(Http.Response r, String token) throws IOException {
        switch (r.status()) {
            case 200, 204 -> { }
            case 401 -> throw new IOException(I18n.tr("skins.error.session"));
            case 404 -> throw new IOException(I18n.tr("ms.noGame"));
            case 429 -> throw new IOException(I18n.tr("skins.error.rateLimit"));
            case 400 -> throw new IOException(I18n.tr("skins.error.invalid"));
            default -> throw new IOException("Minecraft services: HTTP " + r.status());
        }
        if (r.body() == null || r.body().isBlank() || !r.body().trim().startsWith("{")) {
            return parse(JObject.parse(Http.getWithAuth(PROFILE, token).body()));
        }
        return parse(r.json());
    }

    static Profile parse(JObject j) {
        List<Skin> skins = new ArrayList<>();
        for (JObject s : j.arrOrEmpty("skins").objects()) {
            skins.add(new Skin(s.str("id"), s.str("state", ""), s.str("url"), "SLIM".equalsIgnoreCase(s.str("variant", ""))));
        }
        List<Cape> capes = new ArrayList<>();
        for (JObject c : j.arrOrEmpty("capes").objects()) {
            capes.add(new Cape(c.str("id"), c.str("state", ""), c.str("url"), c.str("alias", "Cape")));
        }
        return new Profile(j.str("id"), j.str("name"), skins, capes);
    }
}
