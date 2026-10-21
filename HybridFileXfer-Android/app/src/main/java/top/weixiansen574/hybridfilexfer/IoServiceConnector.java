package top.weixiansen574.hybridfilexfer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ipc.RootService;

import rikka.shizuku.Shizuku;
import top.weixiansen574.hybridfilexfer.aidl.IIOService;

/** Owns exactly one binding. All state and callbacks are confined to the main thread. */
public final class IoServiceConnector implements AutoCloseable {
    public interface Callback {
        void onReady(IIOService service);
        void onFailure(String message);
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Callback callback;
    private int mode;
    private int generation;
    private ServiceConnection connection;
    private boolean requested;
    private boolean active;
    private final Runnable timeout;

    public IoServiceConnector(Context context) {
        this.context = context.getApplicationContext();
        timeout = () -> fail(this.context.getString(R.string.io_start_timeout));
    }

    public void bind(int mode, Callback callback) {
        close();
        this.mode = mode;
        this.callback = callback;
        active = true;
        int attempt = generation;
        connection = new ServiceConnection() {
            private boolean current() { return active && generation == attempt; }
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (!current()) {
                    // libsu cannot remove an in-flight bind task. Release a late connection
                    // using its original identity, never the connection of a later retry.
                    releaseBinding(this, mode, !active || IoServiceConnector.this.mode != mode);
                    return;
                }
                handler.removeCallbacks(timeout);
                try {
                    callback.onReady(IIOService.Stub.asInterface(binder));
                } catch (RuntimeException e) {
                    fail(context.getString(R.string.io_start_failed) + "\n" + e.getMessage());
                }
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if (current()) fail(context.getString(R.string.io_disconnected));
            }
            @Override public void onNullBinding(ComponentName name) {
                if (current()) fail(context.getString(R.string.io_start_failed));
            }
            @Override public void onBindingDied(ComponentName name) {
                if (current()) fail(context.getString(R.string.io_disconnected));
            }
        };
        handler.postDelayed(timeout, 45000);
        if (mode == Config.MODE_ROOT_SU) {
            Shell.EXECUTOR.execute(() -> {
                try {
                    Shell shell = Shell.getShell();
                    boolean root = shell.isRoot();
                    if (!root) {
                        // libsu caches a denied/non-root shell. Allow a later explicit retry.
                        shell.close();
                    }
                    handler.post(() -> {
                        if (!active || generation != attempt) return;
                        if (!root) fail(context.getString(R.string.root_access_denied));
                        else bindNow();
                    });
                } catch (Exception e) {
                    handler.post(() -> {
                        if (active && generation == attempt) {
                            fail(context.getString(R.string.root_access_denied));
                        }
                    });
                }
            });
        } else {
            bindNow();
        }
    }

    private void bindNow() {
        try {
            requested = true;
            if (mode == Config.MODE_ROOT_SU) {
                RootService.bind(new Intent(context, RootIOService.class), connection);
            } else if (mode == Config.MODE_NORMAL) {
                if (!context.bindService(new Intent(context, IOService.class), connection, Context.BIND_AUTO_CREATE)) {
                    fail(context.getString(R.string.io_start_failed));
                }
            } else {
                if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != 0
                        || Shizuku.getUid() != (mode == Config.MODE_ROOT ? 0 : 2000)) {
                    fail(context.getString(R.string.io_start_failed));
                    return;
                }
                Shizuku.bindUserService(IOService.getUserServiceArgs(context), connection);
            }
        } catch (RuntimeException e) {
            fail(context.getString(R.string.io_start_failed) + "\n" + e.getMessage());
        }
    }

    private void fail(String message) {
        if (!active) return;
        Callback listener = callback;
        close();
        if (listener != null) listener.onFailure(message);
    }

    @Override
    public void close() {
        active = false;
        generation++;
        handler.removeCallbacks(timeout);
        callback = null;
        if (!requested) return;
        requested = false;
        releaseBinding(connection, mode, true);
    }

    private void releaseBinding(ServiceConnection binding, int mode, boolean removeService) {
        try {
            if (mode == Config.MODE_ROOT_SU) RootService.unbind(binding);
            else if (mode == Config.MODE_NORMAL) context.unbindService(binding);
            else Shizuku.unbindUserService(IOService.getUserServiceArgs(context), binding, removeService);
        } catch (RuntimeException ignored) {
            // A failed or cancelled bind may not have been registered by the framework.
        }
    }
}
