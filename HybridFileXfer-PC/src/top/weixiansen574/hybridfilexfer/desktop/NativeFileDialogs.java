package top.weixiansen574.hybridfilexfer.desktop;

import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Window;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/** Native save dialogs handle overwrite confirmation according to platform rules; callers write the file. */
public final class NativeFileDialogs {
    private NativeFileDialogs() { }

    /**
     * Shows a modal native Save dialog on the event dispatch thread.
     * Returns the selected absolute path, or null when the user cancels.
     * The suggested file supplies the initial directory and filename; no file is created.
     */
    public static Path chooseSaveFile(Window owner, String title, Path suggestedFile) {
        if (!EventQueue.isDispatchThread())
            throw new IllegalStateException("Native file dialogs must be opened on the event dispatch thread");
        Path initial = Objects.requireNonNull(suggestedFile, "suggestedFile").toAbsolutePath().normalize();
        if (initial.getFileName() == null)
            throw new IllegalArgumentException("The suggested path must include a filename");
        FileDialog dialog = owner instanceof Dialog
                ? new FileDialog((Dialog) owner, title, FileDialog.SAVE)
                : new FileDialog(owner instanceof Frame ? (Frame) owner : null, title, FileDialog.SAVE);
        try {
            if (initial.getParent() != null) dialog.setDirectory(initial.getParent().toString());
            dialog.setFile(initial.getFileName().toString());
            dialog.setMultipleMode(false);
            dialog.setVisible(true);
            String filename = dialog.getFile();
            if (filename == null || filename.isEmpty()) return null;
            String directory = dialog.getDirectory();
            Path selected = directory == null || directory.isEmpty()
                    ? initial.resolveSibling(filename) : Paths.get(directory, filename);
            return selected.toAbsolutePath().normalize();
        } finally {
            // Native dialogs own peers/resources even after cancellation.
            dialog.dispose();
        }
    }
}
