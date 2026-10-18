package com.nekyoh.nekylauncher.ui.kit;

import javax.swing.*;
import java.awt.*;
import java.util.HashSet;
import java.util.Set;

/**
 * Two predictable layouts used everywhere instead of BoxLayout:
 * {@link #v(int)} stacks children vertically at full width; {@link #h(int)} lines them up horizontally,
 * vertically centred, giving spare width to components added with {@link #grow(Component)}.
 */
public class Stack extends JPanel {
    private final boolean vertical;
    private final int gap;
    private final Set<Component> growing = new HashSet<>();

    private Stack(boolean vertical, int gap) {
        this.vertical = vertical;
        this.gap = gap;
        setOpaque(false);
        setLayout(new Lay());
    }

    public static Stack v(int gap) { return new Stack(true, gap); }

    public static Stack h(int gap) { return new Stack(false, gap); }

    public Stack with(Component... cs) {
        for (Component c : cs) super.add(c);
        return this;
    }

    /** Adds a component that takes the remaining space along the main axis. */
    public Stack grow(Component c) {
        super.add(c);
        growing.add(c);
        return this;
    }

    /** Flexible empty space. */
    public Stack glue() {
        JComponent s = new JComponent() { };
        return grow(s);
    }

    public Stack space(int px) {
        Component s = Box.createRigidArea(vertical ? new Dimension(0, px) : new Dimension(px, 0));
        super.add(s);
        return this;
    }

    public Stack pad(int top, int left, int bottom, int right) {
        setBorder(BorderFactory.createEmptyBorder(top, left, bottom, right));
        return this;
    }

    private final class Lay implements LayoutManager {
        @Override public void addLayoutComponent(String name, Component comp) { }

        @Override public void removeLayoutComponent(Component comp) { growing.remove(comp); }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            int main = 0, cross = 0, n = 0;
            int innerW = parent.getWidth() - in.left - in.right;
            for (Component c : parent.getComponents()) {
                if (!c.isVisible()) continue;
                // width-dependent children (wrapped text) measure correctly once they know their width
                if (vertical && innerW > 0 && c.getWidth() != innerW) c.setSize(innerW, Math.max(1, c.getHeight()));
                Dimension d = c.getPreferredSize();
                main += vertical ? d.height : d.width;
                cross = Math.max(cross, vertical ? d.width : d.height);
                n++;
            }
            main += Math.max(0, n - 1) * gap;
            return vertical ? new Dimension(cross + in.left + in.right, main + in.top + in.bottom)
                    : new Dimension(main + in.left + in.right, cross + in.top + in.bottom);
        }

        @Override public Dimension minimumLayoutSize(Container parent) { return preferredLayoutSize(parent); }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            int w = parent.getWidth() - in.left - in.right;
            int h = parent.getHeight() - in.top - in.bottom;
            Component[] cs = parent.getComponents();
            int fixed = 0, n = 0, growCount = 0;
            for (Component c : cs) {
                if (!c.isVisible()) continue;
                n++;
                if (vertical && c.getWidth() != w) c.setSize(w, Math.max(1, c.getHeight()));
                if (growing.contains(c)) growCount++;
                else fixed += vertical ? c.getPreferredSize().height : c.getPreferredSize().width;
            }
            int avail = (vertical ? h : w) - fixed - Math.max(0, n - 1) * gap;
            int growEach = growCount > 0 ? Math.max(0, avail / growCount) : 0;
            int pos = vertical ? in.top : in.left;
            for (Component c : cs) {
                if (!c.isVisible()) continue;
                Dimension d = c.getPreferredSize();
                if (vertical) {
                    int ch = growing.contains(c) ? growEach : d.height;
                    c.setBounds(in.left, pos, w, ch);
                    pos += ch + gap;
                } else {
                    int cw = growing.contains(c) ? growEach : d.width;
                    int ch = Math.min(h, d.height);
                    c.setBounds(pos, in.top + (h - ch) / 2, cw, ch);
                    pos += cw + gap;
                }
            }
        }
    }
}
