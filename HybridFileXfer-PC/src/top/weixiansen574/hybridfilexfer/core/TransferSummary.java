package top.weixiansen574.hybridfilexfer.core;

import java.util.ArrayList;
import java.util.List;

public final class TransferSummary {
    public long completedFiles, skippedFiles, failedFiles, transferredBytes, resumedBytes;
    /** Final distinct-file counts, never inflated by repeat verification rounds. */
    public long verifiedFiles, verificationFailedFiles, unverifiedFiles, retriedFiles;
    public int verificationRounds;
    public boolean cancelled, verificationStopped, verificationEnabled;
    public final List<String> failures = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public void addWarning(String warning) {
        String message=warning.length()>1024?warning.substring(0,1024):warning;
        if (warnings.size()<100) warnings.add(message);
        else warnings.set(99,"Additional filesystem warnings were omitted.");
    }
    public boolean isSuccessful() {
        return !cancelled && !verificationStopped && failedFiles == 0 && unverifiedFiles == 0;
    }
}
