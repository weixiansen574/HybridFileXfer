package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import top.weixiansen574.nio.DataByteChannel;

/** Options are copied at the start of a transfer; UI changes never mutate a running job. */
public final class TransferOptions {
    public enum ConflictPolicy { OVERWRITE, SKIP, KEEP_BOTH }
    public ConflictPolicy conflictPolicy = ConflictPolicy.KEEP_BOTH;
    public boolean verifySha256 = false;
    public boolean resume = true;
    public boolean incremental = false;
    public boolean prescan = false;
    public int timeoutSeconds = 30;

    public TransferOptions copy() {
        TransferOptions o = new TransferOptions();
        o.conflictPolicy = conflictPolicy;
        o.verifySha256 = verifySha256;
        o.resume = resume;
        o.incremental = incremental;
        o.prescan = prescan;
        o.timeoutSeconds = timeoutSeconds;
        o.validate();
        return o;
    }
    public void validate() {
        if (conflictPolicy == null || timeoutSeconds < 5 || timeoutSeconds > 3600)
            throw new IllegalArgumentException("Invalid transfer options");
    }
    public void write(DataByteChannel c) throws IOException {
        validate();
        c.writeInt(conflictPolicy.ordinal());
        c.writeBoolean(verifySha256);
        c.writeBoolean(resume);
        c.writeBoolean(incremental);
        c.writeBoolean(prescan);
        c.writeInt(timeoutSeconds);
    }
    public static TransferOptions read(DataByteChannel c) throws IOException {
        TransferOptions o = new TransferOptions();
        int policy = c.readInt();
        if (policy < 0 || policy >= ConflictPolicy.values().length) throw new IOException("Invalid conflict policy");
        o.conflictPolicy = ConflictPolicy.values()[policy];
        o.verifySha256 = c.readBoolean();
        o.resume = c.readBoolean();
        o.incremental = c.readBoolean();
        o.prescan = c.readBoolean();
        o.timeoutSeconds = c.readInt();
        try { o.validate(); } catch (IllegalArgumentException e) { throw new IOException(e); }
        return o;
    }
}
