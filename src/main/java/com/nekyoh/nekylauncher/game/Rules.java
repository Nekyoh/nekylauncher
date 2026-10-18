package com.nekyoh.nekylauncher.game;

import com.nekyoh.nekylauncher.util.JArray;
import com.nekyoh.nekylauncher.util.JObject;
import com.nekyoh.nekylauncher.util.OS;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Evaluates Mojang "rules" arrays (os name/arch/version and launcher features). */
public final class Rules {
    private Rules() {}

    /** Platform facts used for evaluation; injectable for tests. */
    public record Env(String osName, String osArch, String osVersion) {
        public static final Env CURRENT = new Env(OS.mojangName(), OS.mojangArch(), OS.VERSION);
    }

    public static boolean allowed(JArray rules, Map<String, Boolean> features) {
        return allowed(rules, features, Env.CURRENT);
    }

    public static boolean allowed(JArray rules, Map<String, Boolean> features, Env env) {
        if (rules == null || rules.isEmpty()) return true;
        boolean allow = false;
        for (JObject rule : rules.objects()) {
            if (matches(rule, features, env)) allow = "allow".equals(rule.str("action", "allow"));
        }
        return allow;
    }

    private static boolean matches(JObject rule, Map<String, Boolean> features, Env env) {
        JObject os = rule.obj("os");
        if (os != null) {
            String name = os.str("name");
            if (name != null && !name.equalsIgnoreCase(env.osName())) return false;
            String arch = os.str("arch");
            if (arch != null && !archMatches(arch, env.osArch())) return false;
            String version = os.str("version");
            if (version != null) {
                try {
                    if (!Pattern.compile(version).matcher(env.osVersion()).find()) return false;
                } catch (PatternSyntaxException e) {
                    return false;
                }
            }
        }
        JObject feats = rule.obj("features");
        if (feats != null) {
            for (String k : feats.keys()) {
                boolean want = feats.bool(k, true);
                boolean have = features != null && Boolean.TRUE.equals(features.get(k));
                if (want != have) return false;
            }
        }
        return true;
    }

    private static boolean archMatches(String ruleArch, String arch) {
        // Mojang uses "x86" for 32-bit intel; "arm64"/"aarch64" are equivalent spellings.
        String r = ruleArch.toLowerCase();
        if (r.equals("aarch64")) r = "arm64";
        String a = arch.equals("aarch64") ? "arm64" : arch;
        return r.equals(a);
    }
}
