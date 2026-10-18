package com.nekyoh.nekylauncher.ui;

import com.nekyoh.nekylauncher.auth.Account;
import com.nekyoh.nekylauncher.i18n.I18n;

/** Localized formatting helpers shared by pages. */
public final class UiText {
    private UiText() {}

    public static String accountType(Account a) {
        return I18n.tr("account.type.microsoft");
    }

    /** "just now", "5 min ago", "3 h ago", "2 days ago", or "never played". */
    public static String lastPlayed(long epochMs) {
        if (epochMs <= 0) return I18n.tr("time.never");
        long s = Math.max(0, (System.currentTimeMillis() - epochMs) / 1000);
        if (s < 60) return I18n.tr("time.justNow");
        if (s < 3600) return I18n.tr("time.minutesAgo", s / 60);
        if (s < 86400) return I18n.tr("time.hoursAgo", s / 3600);
        return I18n.tr("time.daysAgo", s / 86400);
    }

    public static String playTime(long seconds) {
        if (seconds < 60) return I18n.tr("time.lessThanMinute");
        long h = seconds / 3600, m = (seconds % 3600) / 60;
        return h > 0 ? I18n.tr("time.hm", h, m) : I18n.tr("time.m", m);
    }

    public static String count(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
        return Long.toString(n);
    }
}
