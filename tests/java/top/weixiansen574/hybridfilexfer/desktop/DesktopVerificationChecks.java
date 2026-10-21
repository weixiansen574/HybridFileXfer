package top.weixiansen574.hybridfilexfer.desktop;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import top.weixiansen574.hybridfilexfer.core.TransferProgress;
import top.weixiansen574.hybridfilexfer.core.TransferSummary;
import top.weixiansen574.hybridfilexfer.core.VerificationProgress;

/** Real widgets: cancellation mode, round counts and late-event protection without device access. */
final class DesktopVerificationChecks {
    private static int checks;

    @SuppressWarnings("unchecked")
    static void verify(DesktopApp app) throws Exception {
        check(!((JCheckBox) get(app, "verify")).isSelected(), "SHA is opt-in");
        call(app, "started", new Class<?>[]{boolean.class}, true);
        JButton stop = (JButton) get(app, "cancelTransfer");
        JProgressBar progress = (JProgressBar) get(app, "progress");
        JLabel phase = (JLabel) get(app, "phase");
        VerificationProgress waiting = new VerificationProgress(1, 10, 3, 2, 1, "waiting-peer", "synthetic.bin");
        call(app, "showVerification", new Class<?>[]{VerificationProgress.class}, waiting);
        check(stop.isEnabled() && stop.getText().equals(DesktopText.text("停止校验与重试", "Stop verification & retries")),
                "Verification exposes its separate stop action");
        check(progress.getValue() == 300, "Progress counts compared files, not local hashes");
        check(phase.getText().contains("3") && phase.getText().contains("2")
                && phase.getText().contains("1") && phase.getText().contains("7"), "All round counts are visible");
        String waitingText = progress.getString();
        TransferProgress lateData = new TransferProgress();
        lateData.totalBytes = 100; lateData.transferredBytes = 100;
        call(app, "showProgress", new Class<?>[]{TransferProgress.class}, lateData);
        check(progress.getString().equals(waitingText), "Late byte progress cannot overwrite verification");
        VerificationProgress retry = new VerificationProgress(1, 10, 10, 9, 1, "retrying", "retry.bin");
        call(app, "showVerification", new Class<?>[]{VerificationProgress.class}, retry);
        check(progress.isIndeterminate() && stop.isEnabled(), "Retry remains stoppable without hard cancellation");
        stop.doClick();
        check(Boolean.TRUE.equals(get(app, "verificationStopRequested")), "Stop chooses the verification path");
        check(!stop.isEnabled(), "Repeated stops are disabled");
        call(app, "showVerification", new Class<?>[]{VerificationProgress.class}, waiting);
        check(!stop.isEnabled(), "Late progress cannot re-enable a requested stop");
        TransferSummary summary = new TransferSummary();
        summary.verificationEnabled = true; summary.verificationStopped = true;
        summary.completedFiles = 9; summary.verifiedFiles = 2; summary.verificationFailedFiles = 1;
        summary.failedFiles = 1; summary.unverifiedFiles = 7; summary.verificationRounds = 1;
        call(app, "showSummary", new Class<?>[]{TransferSummary.class}, summary);
        call(app, "verificationStopped", new Class<?>[]{boolean.class, long.class, long.class}, true, 100L, 200L);
        String stoppedText = progress.getString(), stoppedCounts = phase.getText();
        check(!Boolean.TRUE.equals(get(app, "transferActive")) && !progress.isIndeterminate(), "Soft stop ends UI work");
        check(stoppedCounts.contains("2") && stoppedCounts.contains("1") && stoppedCounts.contains("7"),
                "Stopped summary preserves passed, failed and unchecked counts");
        ((AtomicReference<VerificationProgress>) get(app, "latestVerification")).set(retry);
        ((AtomicReference<TransferProgress>) get(app, "latestProgress")).set(lateData);
        call(app, "flushProgress", new Class<?>[0]);
        check(progress.getString().equals(stoppedText) && phase.getText().equals(stoppedCounts)
                && !stop.isEnabled(), "Queued progress cannot revive a finished task");
        // Emulate a fast worker publishing verification before the queued EDT start runs.
        ((AtomicReference<VerificationProgress>) get(app, "latestVerification")).set(waiting);
        call(app, "started", new Class<?>[]{boolean.class}, false);
        check(!Boolean.TRUE.equals(get(app, "verificationActive")) && stop.isEnabled(),
                "The next task begins in data-transfer mode");
        // A first verification snapshot may arrive just before a click, before the timer flushes.
        stop.doClick();
        check(Boolean.TRUE.equals(get(app, "verificationStopRequested")),
                "A pending first snapshot switches cancellation mode before the click is handled");
        System.out.println("Desktop verification checks passed: " + checks);
    }

    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    private static void call(DesktopApp owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = DesktopApp.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(owner, args);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
