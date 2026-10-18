package com.nekyoh.nekylauncher.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * UI translations. Bundles live in resources/nekylauncher/i18n/messages_xx.properties (UTF-8).
 * English is the fallback for any missing key.
 */
public final class I18n {
    private I18n() {}

    public enum Lang {
        EN("en", "English"), PT("pt", "Português (Brasil)"), ES("es", "Español");

        public final String code;
        public final String display;

        Lang(String code, String display) {
            this.code = code;
            this.display = display;
        }

        public static Lang of(String code) {
            for (Lang l : values()) if (l.code.equalsIgnoreCase(code)) return l;
            return EN;
        }

        @Override
        public String toString() { return display; }
    }

    private static final String BASE = "nekylauncher.i18n.messages";
    private static volatile Lang current = Lang.EN;
    private static volatile ResourceBundle bundle = load(Lang.EN);
    private static final ResourceBundle FALLBACK = load(Lang.EN);

    private static ResourceBundle load(Lang l) {
        Locale loc = l == Lang.EN ? Locale.ROOT : Locale.forLanguageTag(l.code);
        return ResourceBundle.getBundle(BASE, loc, I18n.class.getClassLoader(),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    public static void set(Lang l) {
        current = l;
        bundle = load(l);
        Locale.setDefault(Locale.Category.FORMAT, l == Lang.PT ? Locale.forLanguageTag("pt-BR") : Locale.forLanguageTag(l.code));
    }

    public static Lang current() { return current; }

    public static String tr(String key, Object... args) {
        String s;
        try {
            s = bundle.getString(key);
        } catch (MissingResourceException e) {
            try {
                s = FALLBACK.getString(key);
            } catch (MissingResourceException e2) {
                return key;
            }
        }
        if (args.length == 0) return s;
        try {
            return new MessageFormat(s.replace("'", "''"), Locale.ROOT).format(args);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
