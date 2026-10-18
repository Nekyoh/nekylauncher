package com.nekyoh.nekylauncher.ui.kit;

import java.awt.*;

/** Responsive grid: as many columns of at least {@code minCell} px as fit, cells stretched to fill. */
public final class GridFlow implements LayoutManager {
    private final int minCell;
    private final int cellHeight;
    private final int gap;

    public GridFlow(int minCell, int cellHeight, int gap) {
        this.minCell = minCell;
        this.cellHeight = cellHeight;
        this.gap = gap;
    }

    private int columns(int width) { return Math.max(1, (width + gap) / (minCell + gap)); }

    @Override public void addLayoutComponent(String name, Component comp) { }

    @Override public void removeLayoutComponent(Component comp) { }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        Insets in = parent.getInsets();
        int width = parent.getWidth() > 0 ? parent.getWidth() - in.left - in.right : minCell * 3 + gap * 2;
        int n = visible(parent);
        int cols = columns(width);
        int rows = (n + cols - 1) / cols;
        int h = rows == 0 ? 0 : rows * cellHeight + (rows - 1) * gap;
        return new Dimension(Math.min(width, minCell) + in.left + in.right, h + in.top + in.bottom);
    }

    @Override public Dimension minimumLayoutSize(Container parent) { return preferredLayoutSize(parent); }

    @Override
    public void layoutContainer(Container parent) {
        Insets in = parent.getInsets();
        int width = parent.getWidth() - in.left - in.right;
        int cols = columns(width);
        int cw = (width - (cols - 1) * gap) / cols;
        int i = 0;
        for (Component c : parent.getComponents()) {
            if (!c.isVisible()) continue;
            int r = i / cols, col = i % cols;
            c.setBounds(in.left + col * (cw + gap), in.top + r * (cellHeight + gap), cw, cellHeight);
            i++;
        }
    }

    private static int visible(Container p) {
        int n = 0;
        for (Component c : p.getComponents()) if (c.isVisible()) n++;
        return n;
    }
}
