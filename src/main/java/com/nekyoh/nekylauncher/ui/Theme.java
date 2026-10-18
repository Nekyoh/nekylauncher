package com.nekyoh.nekylauncher.ui;

import com.nekyoh.nekylauncher.util.Log;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;
import java.awt.*;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/** Colors, fonts and global Swing defaults for the NekyLauncher dark theme. */
public final class Theme {
    private Theme() {}

    // surfaces
    public static final Color BG = new Color(0x0B0D12);
    public static final Color SIDEBAR = new Color(0x0F1218);
    public static final Color SURFACE = new Color(0x141821);
    public static final Color SURFACE_HOVER = new Color(0x191E29);
    public static final Color SURFACE_2 = new Color(0x1B202B);
    public static final Color SURFACE_3 = new Color(0x242A37);
    public static final Color BORDER = new Color(0x222834);
    public static final Color BORDER_STRONG = new Color(0x313847);

    // text
    public static final Color TEXT = new Color(0xE9ECF1);
    public static final Color TEXT_2 = new Color(0xA0A8B7);
    public static final Color TEXT_3 = new Color(0x6A7384);

    // brand (from the logo's glitch palette) and states
    public static final Color CYAN = new Color(0x22D3EE);
    public static final Color CYAN_DARK = new Color(0x0891B2);
    public static final Color RED = new Color(0xF43F5E);
    public static final Color GREEN = new Color(0x22C55E);
    public static final Color GREEN_DARK = new Color(0x15803D);
    public static final Color AMBER = new Color(0xF59E0B);
    public static final Color ON_CYAN = new Color(0x052129);

    public static final int RADIUS = 12;
    public static final int RADIUS_SM = 9;

    public enum Weight { REGULAR, MEDIUM, SEMIBOLD, BOLD }

    private static final Map<Weight, Font> BASE = new EnumMap<>(Weight.class);
    private static Font mono;

    public static void install() {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        loadFonts();
        try {
            MetalLookAndFeel.setCurrentTheme(new DarkMetal());
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (Exception e) {
            Log.error("Look and feel failed", e);
        }
        Font body = font(13, Weight.REGULAR);
        FontUIResource f = new FontUIResource(body);
        for (Object key : UIManager.getLookAndFeelDefaults().keySet().toArray()) {
            if (key != null && key.toString().endsWith(".font")) UIManager.put(key, f);
        }
        put("Panel.background", BG);
        put("Label.foreground", TEXT);
        put("ToolTip.background", SURFACE_3);
        put("ToolTip.foreground", TEXT);
        UIManager.put("ToolTip.border", new javax.swing.border.CompoundBorder(new LineBorder(BORDER_STRONG), new EmptyBorder(5, 8, 5, 8)));
        put("PopupMenu.background", SURFACE_2);
        UIManager.put("PopupMenu.border", new javax.swing.border.CompoundBorder(new LineBorder(BORDER_STRONG), new EmptyBorder(4, 0, 4, 0)));
        put("MenuItem.background", SURFACE_2);
        put("MenuItem.foreground", TEXT);
        put("MenuItem.selectionBackground", SURFACE_3);
        put("MenuItem.selectionForeground", TEXT);
        put("MenuItem.acceleratorForeground", TEXT_3);
        UIManager.put("MenuItem.border", new EmptyBorder(7, 10, 7, 18));
        put("Separator.foreground", BORDER);
        put("Separator.background", SURFACE_2);
        put("PopupMenuSeparator.foreground", BORDER);
        put("PopupMenuSeparator.background", SURFACE_2);
        put("TextField.background", SURFACE_2);
        put("TextField.foreground", TEXT);
        put("TextField.caretForeground", CYAN);
        put("TextField.selectionBackground", new Color(0x155E75));
        put("TextField.selectionForeground", TEXT);
        put("TextField.inactiveForeground", TEXT_3);
        put("PasswordField.background", SURFACE_2);
        put("PasswordField.foreground", TEXT);
        put("PasswordField.caretForeground", CYAN);
        put("PasswordField.selectionBackground", new Color(0x155E75));
        put("TextArea.background", SURFACE_2);
        put("TextArea.foreground", TEXT);
        put("TextArea.caretForeground", CYAN);
        put("TextArea.selectionBackground", new Color(0x155E75));
        put("TextPane.background", SURFACE);
        put("TextPane.foreground", TEXT);
        put("TextPane.selectionBackground", new Color(0x155E75));
        put("List.background", SURFACE_2);
        put("List.foreground", TEXT);
        put("List.selectionBackground", SURFACE_3);
        put("List.selectionForeground", TEXT);
        put("ComboBox.background", SURFACE_2);
        put("ComboBox.foreground", TEXT);
        put("ComboBox.selectionBackground", SURFACE_3);
        put("ComboBox.selectionForeground", TEXT);
        put("ScrollPane.background", BG);
        put("Viewport.background", BG);
        put("OptionPane.background", SURFACE);
        put("OptionPane.messageForeground", TEXT);
        put("CheckBox.background", SURFACE);
        put("CheckBox.foreground", TEXT);
        put("FileChooser.background", SURFACE);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ToolTip.font", new FontUIResource(font(12, Weight.REGULAR)));
        ToolTipManager.sharedInstance().setInitialDelay(450);
    }

    private static void put(String key, Color c) { UIManager.put(key, new ColorUIResource(c)); }

    private static void loadFonts() {
        String[][] files = {{"REGULAR", "Inter-Regular.otf"}, {"MEDIUM", "Inter-Medium.otf"},
                {"SEMIBOLD", "Inter-SemiBold.otf"}, {"BOLD", "Inter-Bold.otf"}};
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (String[] f : files) {
            try (InputStream in = Theme.class.getResourceAsStream("/nekylauncher/fonts/" + f[1])) {
                if (in == null) continue;
                Font font = Font.createFont(Font.TRUETYPE_FONT, in);
                ge.registerFont(font);
                BASE.put(Weight.valueOf(f[0]), font);
            } catch (Exception e) {
                Log.warn("Font " + f[1] + " failed: " + e.getMessage());
            }
        }
        String[] monos = {"JetBrains Mono", "Cascadia Mono", "Consolas", "DejaVu Sans Mono", "Liberation Mono"};
        java.util.Set<String> available = java.util.Set.of(ge.getAvailableFontFamilyNames());
        for (String m : monos) {
            if (available.contains(m)) { mono = new Font(m, Font.PLAIN, 12); break; }
        }
        if (mono == null) mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    }

    public static Font font(float size, Weight w) {
        Font base = BASE.get(w);
        if (base == null) base = BASE.get(Weight.REGULAR);
        if (base == null) return new Font(Font.SANS_SERIF, w.ordinal() >= 2 ? Font.BOLD : Font.PLAIN, Math.round(size));
        return base.deriveFont(size);
    }

    public static Font font(float size) { return font(size, Weight.REGULAR); }

    public static Font mono(float size) { return mono.deriveFont(size); }

    /** High-quality rendering hints for custom painting. */
    public static Graphics2D hq(Graphics g) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        return g2;
    }

    public static Color alpha(Color c, double a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(Math.max(0, Math.min(1, a)) * 255));
    }

    public static Color mix(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t),
                (int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    /** Draws text with the logo's red/cyan chromatic offset. */
    public static void glitchText(Graphics2D g, String s, float x, float y, float offset) {
        Color old = g.getColor();
        g.setColor(alpha(RED, 0.85));
        g.drawString(s, x + offset, y);
        g.setColor(alpha(CYAN, 0.85));
        g.drawString(s, x - offset, y);
        g.setColor(old);
        g.drawString(s, x, y);
    }

    private static final class DarkMetal extends DefaultMetalTheme {
        @Override public String getName() { return "Neky"; }
        @Override protected ColorUIResource getPrimary1() { return new ColorUIResource(CYAN); }
        @Override protected ColorUIResource getPrimary2() { return new ColorUIResource(SURFACE_3); }
        @Override protected ColorUIResource getPrimary3() { return new ColorUIResource(SURFACE_3); }
        @Override protected ColorUIResource getSecondary1() { return new ColorUIResource(BORDER_STRONG); }
        @Override protected ColorUIResource getSecondary2() { return new ColorUIResource(BORDER); }
        @Override protected ColorUIResource getSecondary3() { return new ColorUIResource(SURFACE); }
        @Override protected ColorUIResource getBlack() { return new ColorUIResource(TEXT); }
        @Override protected ColorUIResource getWhite() { return new ColorUIResource(SURFACE_2); }
        @Override public ColorUIResource getControlTextColor() { return new ColorUIResource(TEXT); }
        @Override public ColorUIResource getSystemTextColor() { return new ColorUIResource(TEXT); }
        @Override public ColorUIResource getUserTextColor() { return new ColorUIResource(TEXT); }
        @Override public ColorUIResource getMenuForeground() { return new ColorUIResource(TEXT); }
        @Override public ColorUIResource getWindowBackground() { return new ColorUIResource(SURFACE); }
        @Override public ColorUIResource getInactiveSystemTextColor() { return new ColorUIResource(TEXT_3); }
        @Override public ColorUIResource getControlDisabled() { return new ColorUIResource(TEXT_3); }
        @Override public FontUIResource getControlTextFont() { return new FontUIResource(font(13)); }
        @Override public FontUIResource getUserTextFont() { return new FontUIResource(font(13)); }
        @Override public FontUIResource getMenuTextFont() { return new FontUIResource(font(13)); }
        @Override public FontUIResource getSystemTextFont() { return new FontUIResource(font(13)); }
        @Override public FontUIResource getWindowTitleFont() { return new FontUIResource(font(13, Weight.SEMIBOLD)); }
        @Override public FontUIResource getSubTextFont() { return new FontUIResource(font(11)); }
    }
}
