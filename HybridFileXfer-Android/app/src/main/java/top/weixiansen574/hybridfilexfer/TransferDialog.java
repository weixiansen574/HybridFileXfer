package top.weixiansen574.hybridfilexfer;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;

public class TransferDialog {
    /** 进度文本最快更新间隔。传输线程每秒会产生上百次进度回调，全部推到 UI 线程没有意义 */
    private static final long PROGRESS_INTERVAL_MS = 100;

    public final boolean isUpload;
    private final Map<String, Holder> holderMap;
    private final Context context;
    private final View dialogView;
    private AlertDialog dialog;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> lastProgressTime = new HashMap<>();


    public TransferDialog(Context context, boolean isUpload, List<String> channelNames) {
        this.context = context;
        holderMap = new HashMap<>();
        LayoutInflater inflater = LayoutInflater.from(context);
        dialogView = View.inflate(context, R.layout.dialog_transfer, null);
        LinearLayout linearLayout = dialogView.findViewById(R.id.linearLayout);
        for (String channelName : channelNames) {
            View itemView = inflater.inflate(R.layout.item_net_interface_status, linearLayout, false);
            //只保留上或下的其中一个速度指示器
            if (isUpload) {
                itemView.findViewById(R.id.download_speed).setVisibility(View.GONE);
            } else {
                itemView.findViewById(R.id.upload_speed).setVisibility(View.GONE);
            }
            Holder holder = new Holder(itemView);
            holder.channelName.setText(channelName);
            holder.channelIcon.setImageDrawable(ContextCompat.getDrawable(context, NetCardIcon.matchIdForIName(channelName)));
            holderMap.put(channelName, holder);
            linearLayout.addView(itemView);
        }
        this.isUpload = isUpload;
    }


    public void show() {
        dialog = new AlertDialog.Builder(context)
                .setTitle(isUpload ? context.getString(R.string.uploading_speed_zero) :
                        context.getString(R.string.downloading_speed_zero))
                .setView(dialogView)
                .setPositiveButton(R.string.complete, null)
                .setCancelable(false)
                .show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);
    }

    public void showEvent(String iName, String event) {
        Objects.requireNonNull(holderMap.get(iName)).transferEvent.setText(event);
    }

    /**
     * 传输进度专用：所有进度文本都来自传输线程，直接 setText 会：
     * 1) 让传输线程卡在 measure/layout 上（每块一次）；
     * 2) 跨线程改 View，长度变化时触发 requestLayout，属于未定义行为。
     * 这里改成投递到主线程 + 100ms 节流。
     */
    public void showProgress(String iName, String event) {
        final Holder holder = holderMap.get(iName);
        if (holder == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        Long last = lastProgressTime.get(iName);
        if (last != null && now - last < PROGRESS_INTERVAL_MS) {
            return;
        }
        lastProgressTime.put(iName, now);
        mainHandler.post(() -> holder.transferEvent.setText(event));
    }

    public void showSpeeds(List<TrafficInfo> trafficInfoList) {
        long totalUploadSpeed = 0;
        long totalDownloadSpeed = 0;
        for (TrafficInfo trafficInfo : trafficInfoList) {
            Holder holder = holderMap.get(trafficInfo.iName);
            if (holder != null) {
                holder.uploadSpeed.setText(Utils.formatSpeed(trafficInfo.uploadTraffic));
                holder.downloadSpeed.setText(Utils.formatSpeed(trafficInfo.downloadTraffic));
                totalUploadSpeed += trafficInfo.uploadTraffic;
                totalDownloadSpeed += trafficInfo.downloadTraffic;
            }
        }
        if (isUpload) {
            dialog.setTitle(context.getString(R.string.uploading_speed,
                    ((float) totalUploadSpeed) / 1024 / 1024));
        } else {
            dialog.setTitle(context.getString(R.string.downloading_speed,
                    ((float) totalDownloadSpeed) / 1024 / 1024));
        }
    }

    public void complete(boolean isUpload, long traffic, long time) {
        setCloseBtnEnable(true);
        dialog.setTitle((isUpload ? "▲" : "▼") + Utils.formatSpeed(traffic / time * 1000) +
                "·" + Utils.formatTime(time) + "·" + Utils.formatFileSize(traffic));
        for (Map.Entry<String, Holder> entry : holderMap.entrySet()) {
            Holder holder = entry.getValue();
            holder.downloadSpeed.setText(Utils.formatSpeed(0));
            holder.uploadSpeed.setText(Utils.formatSpeed(0));
        }
    }

    public void setTitle(String title) {
        dialog.setTitle(title);
    }

    public void setCloseBtnEnable(boolean enable) {
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(enable);
    }

    public void setButton(String text, View.OnClickListener onClickListener) {
        Button button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        button.setText(text);
        button.setOnClickListener(onClickListener);
    }

    public void dismiss() {
        dialog.dismiss();
    }

    public AlertDialog getDialog() {
        return dialog;
    }

    private static class Holder {
        final View itemView;
        ImageView channelIcon;
        TextView channelName, uploadSpeed, downloadSpeed, transferEvent;

        public Holder(View itemView) {
            this.itemView = itemView;
            channelIcon = itemView.findViewById(R.id.channel_icon);
            channelName = itemView.findViewById(R.id.channel_name);
            uploadSpeed = itemView.findViewById(R.id.upload_speed);
            downloadSpeed = itemView.findViewById(R.id.download_speed);
            transferEvent = itemView.findViewById(R.id.txv_transfer_event);
        }
    }
}
