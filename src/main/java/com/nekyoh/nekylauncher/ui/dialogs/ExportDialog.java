package com.nekyoh.nekylauncher.ui.dialogs;

import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.instance.Instance;
import com.nekyoh.nekylauncher.instance.InstanceArchive;
import com.nekyoh.nekylauncher.ui.Icons;
import com.nekyoh.nekylauncher.ui.Theme;
import com.nekyoh.nekylauncher.ui.kit.*;
import com.nekyoh.nekylauncher.util.FileUtil;
import com.nekyoh.nekylauncher.util.OS;
import com.nekyoh.nekylauncher.util.Progress;
import com.nekyoh.nekylauncher.util.Tasks;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Choose which parts of the instance folder go into the .zip, then export with progress. */
public final class ExportDialog {
    private ExportDialog() {}

    public static void open(Instance inst) {
        Stack list = Stack.v(4);
        JLabel loading = Ui.small(I18n.tr("common.loading"));
        list.with(loading);
        JScrollPane sp = NScroll.vertical(list, Theme.SURFACE);
        sp.setPreferredSize(new Dimension(460, 300));
        Map<String, JCheckBox> boxes = new LinkedHashMap<>();
        JLabel stage = Ui.small("");
        Controls.Progress bar = new Controls.Progress();
        bar.setVisible(false);
        Stack body = Stack.v(12);
        body.with(Ui.paragraph(I18n.tr("export.desc"), Theme.TEXT_2, 13), sp, stage, bar);
        Modal m = Modal.open(I18n.tr("export.title", inst.name), body, 540);
        NButton export = new NButton(I18n.tr("export.button"), Icons.Name.EXPORT, NButton.Variant.PRIMARY);
        m.footer(new NButton(I18n.tr("common.cancel"), NButton.Variant.GHOST).onClick(m::close), export);

        Tasks.async(() -> InstanceArchive.exportables(inst), (List<InstanceArchive.Entry> entries) -> {
            list.removeAll();
            for (InstanceArchive.Entry e : entries) {
                boolean def = !InstanceArchive.DEFAULT_EXCLUDED.contains(e.name());
                JCheckBox cb = Controls.checkbox(e.name() + (e.directory() ? "/" : "") + "   ·   " + FileUtil.humanSize(e.size()), def);
                boxes.put(e.name(), cb);
                list.with(cb);
            }
            if (entries.isEmpty()) list.with(Ui.small(I18n.tr("export.empty")));
            list.revalidate();
            list.repaint();
        }, null);

        export.onClick(() -> {
            Set<String> include = new LinkedHashSet<>();
            boxes.forEach((k, v) -> { if (v.isSelected()) include.add(k); });
            Path target = Pickers.saveFile(I18n.tr("export.saveAs"), InstanceArchive.exportFileName(inst));
            if (target == null) return;
            export.setEnabled(false);
            bar.setVisible(true);
            m.relayout();
            Progress p = new Progress() {
                public void stage(String text) { SwingUtilities.invokeLater(() -> stage.setText(text)); }
                public void fraction(double v) { SwingUtilities.invokeLater(() -> bar.setValue(v)); }
            };
            Tasks.async(() -> InstanceArchive.export(inst, target, include, p), () -> {
                m.close();
                Toast.show(I18n.tr("export.done", target.getFileName()), Toast.Kind.SUCCESS, I18n.tr("export.show"), () -> OS.revealFile(target));
            }, err -> {
                export.setEnabled(true);
                Modal.error(I18n.tr("export.failed"), Tasks.message(err));
            });
        });
    }
}
