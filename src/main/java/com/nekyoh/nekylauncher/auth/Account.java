package com.nekyoh.nekylauncher.auth;

import com.nekyoh.nekylauncher.util.JObject;

import java.util.UUID;

/** A Microsoft account that owns Minecraft: Java Edition. */
public final class Account {
    /** Only the original game is supported: Microsoft accounts. Kept as an enum for forward compatibility. */
    public enum Type { MICROSOFT }

    public String id;           // local id (uuid string)
    public Type type = Type.MICROSOFT;
    public String username;
    public String uuid;         // without dashes
    public String accessToken = "";
    public String refreshToken = "";
    public long expiresAt;      // epoch millis of accessToken expiry (0 = unknown)
    public String xuid = "";
    public String skinUrl = "";

    public String dashedUuid() {
        if (uuid == null || uuid.length() != 32) return uuid;
        return uuid.substring(0, 8) + "-" + uuid.substring(8, 12) + "-" + uuid.substring(12, 16) + "-"
                + uuid.substring(16, 20) + "-" + uuid.substring(20);
    }

    /** Value for ${user_type}. */
    public String userType() { return "msa"; }

    public boolean needsRefresh() {
        return expiresAt == 0 || System.currentTimeMillis() > expiresAt - 5 * 60_000L;
    }

    public JObject toJson() {
        return new JObject()
                .put("id", id).put("type", type.name()).put("username", username).put("uuid", uuid)
                .put("accessToken", accessToken).put("refreshToken", refreshToken)
                .put("expiresAt", expiresAt).put("xuid", xuid).put("skinUrl", skinUrl);
    }

    /** Returns null for entries that are not Microsoft accounts (e.g. from older builds). */
    public static Account fromJson(JObject o) {
        if (!"MICROSOFT".equals(o.str("type", ""))) return null;
        if (o.str("uuid") == null || o.str("refreshToken", "").isBlank()) return null;
        Account a = new Account();
        a.id = o.str("id", UUID.randomUUID().toString());
        a.username = o.str("username", "Player");
        a.uuid = o.str("uuid");
        a.accessToken = o.str("accessToken", "");
        a.refreshToken = o.str("refreshToken", "");
        a.expiresAt = o.lng("expiresAt", 0);
        a.xuid = o.str("xuid", "");
        a.skinUrl = o.str("skinUrl", "");
        return a;
    }
}
