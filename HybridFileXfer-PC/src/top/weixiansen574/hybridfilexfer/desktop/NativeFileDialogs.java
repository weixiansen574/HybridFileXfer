package top.weixiansen574.hybridfilexfer.desktop;

import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Window;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/** 封装系统原生文件保存对话框。 */
public final class NativeFileDialogs {
    private NativeFileDialogs() { }

    /**
     * 在事件派发线程显示模态原生保存对话框。
     * 返回用户选择的绝对路径，取消时返回 null。
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
            // 关闭后及时释放原生对话框 peer 资源。
            dialog.dispose();
        }
    }
}
