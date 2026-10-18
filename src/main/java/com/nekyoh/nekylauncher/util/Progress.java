package com.nekyoh.nekylauncher.util;

/** Progress sink for long-running tasks. Implementations must be thread-safe. */
public interface Progress {
    /** A new phase started; {@code text} is already localized. */
    void stage(String text);

    /** Fraction in [0,1], or a negative value for indeterminate. */
    void fraction(double value);

    default void detail(String text) { }

    Progress NONE = new Progress() {
        public void stage(String text) { }
        public void fraction(double value) { }
    };
}
