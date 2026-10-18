package com.nekyoh.nekylauncher.ui.dialogs;

import com.nekyoh.nekylauncher.App;
import com.nekyoh.nekylauncher.i18n.I18n;
import com.nekyoh.nekylauncher.instance.InstanceArchive;
import com.nekyoh.nekylauncher.ui.MainWindow;
import com.nekyoh.nekylauncher.ui.kit.*;
import com.nekyoh.nekylauncher.util.CancelToken;
import com.nekyoh.nekylauncher.util.Progress;
import com.nekyoh.nekylauncher.util.Tasks;

import javax.swing.*;
import java.nio.file.Path;

/** Import of .zip / .mrpack archives with a progress modal. */
public final class ImportFlow {
    private ImportFlow() {}

    public static void pickAndImport(MainWindow window) {
        Path p = Pickers.openFile(I18n.tr("import.pick"), "zip", "mrpack");
        if (p != null) importFile(window, p);
    }

    public static void importFile(MainWindow window, Path file) {
        JLabel stage = Ui.muted(I18n.tr("progress.importing"));
        Controls.Progress bar = new Controls.Progress();
        Stack body = Stack.v(12);
        body.with(Ui.small(file.getFileName().toString()), stage, bar);
        Modal m = Modal.open(I18n.tr("import.title"), body, 460);
        CancelToken cancel = new CancelToken();
        m.onClose(cancel::cancel);
        m.footer(new NButton(I18n.tr("common.cancel"), NButton.Variant.GHOST).onClick(m::close));
        Progress progress = new Progress() {
            public void stage(String text) { SwingUtilities.invokeLater(() -> stage.setText(text)); }
            public void fraction(double v) { SwingUtilities.invokeLater(() -> bar.setValue(v)); }
        };
        Tasks.async(() -> InstanceArchive.importArchive(file, App.get().instances, progress, cancel), res -> {
            m.escClosable(true);
            m.onClose(null);
            m.close();
            if (!res.warnings().isEmpty()) {
                Modal.message(I18n.tr("import.warningsTitle"), I18n.tr("import.warnings", res.warnings().size()) + "\n\n"
                        + String.join("\n", res.warnings().stream().limit(15).toList()));
            }
            Toast.show(I18n.tr("import.done", res.instance().name), Toast.Kind.SUCCESS, I18n.tr("instance.play"), () -> window.launch.play(res.instance()));
            window.openInstance(res.instance());
        }, err -> {
            m.onClose(null);
            m.close();
            if (!(err instanceof CancelToken.CancelledException)) Modal.error(I18n.tr("import.failed"), Tasks.message(err));
        });
    }

}
