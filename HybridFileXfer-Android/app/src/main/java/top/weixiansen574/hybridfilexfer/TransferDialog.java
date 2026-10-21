package top.weixiansen574.hybridfilexfer;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
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
    public final boolean isUpload;
    private final Map<String, Holder> holderMap;
    private final Context context;
    private final View dialogView;
    private AlertDialog dialog;
    private final android.widget.ProgressBar progressBar;
    private final TextView progressText;
    private Runnable cancel;
    private boolean verificationMode;


    public TransferDialog(Context context, boolean isUpload, List<String> channelNames) {
        this.context = context;
        holderMap = new HashMap<>();
        LayoutInflater inflater = LayoutInflater.from(context);
        dialogView = View.inflate(context, R.layout.dialog_transfer, null);
        progressBar = dialogView.findViewById(R.id.transfer_progress);
        progressText = dialogView.findViewById(R.id.transfer_progress_text);
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
                .setNegativeButton(R.string.cancel_transfer, null)
                .setCancelable(false)
                .show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> {
            if (cancel != null) cancel.run();
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(false);
        });
    }

    public void showEvent(String iName, String event) {
        Holder holder = holderMap.get(iName);
        if (holder != null) holder.transferEvent.setText(event);
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
        if (verificationMode) return;
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
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setVisibility(View.GONE);
        dialog.setTitle((isUpload ? "▲" : "▼") + Utils.formatSpeed(traffic * 1000 / Math.max(1, time)) +
                "·" + Utils.formatTime(time) + "·" + Utils.formatFileSize(traffic));
        for (Map.Entry<String, Holder> entry : holderMap.entrySet()) {
            Holder holder = entry.getValue();
            holder.downloadSpeed.setText(Utils.formatSpeed(0));
            holder.uploadSpeed.setText(Utils.formatSpeed(0));
        }
    }

    public void setOnCancel(Runnable cancel) { this.cancel = cancel; }

    public void showProgress(top.weixiansen574.hybridfilexfer.core.TransferProgress progress, String phase) {
        verificationMode = false;
        if (progress == null) {
            progressBar.setIndeterminate(true);
            progressText.setText(phase);
            return;
        }
        progressBar.setIndeterminate(progress.totalBytes < 0);
        if (progress.totalBytes >= 0) {
            progressBar.setMax(1000);
            progressBar.setProgress(progress.totalBytes == 0 ? 1000 :
                    (int) Math.min(1000, progress.transferredBytes * 1000.0 / progress.totalBytes));
        }
        String total = progress.totalBytes < 0 ? "?" : Utils.formatFileSize(progress.totalBytes);
        String count = progress.totalFiles < 0 ? "?" : String.valueOf(progress.totalFiles);
        progressText.setText(Utils.formatFileSize(progress.transferredBytes) + " / " + total + " · "
                + progress.completedFiles + " / " + count + "\n"
                + (progress.currentPath == null ? "" : progress.currentPath) + "\n" + phase);
    }

    public void showVerification(top.weixiansen574.hybridfilexfer.core.VerificationProgress progress,
                                 boolean stopping) {
        verificationMode = true;
        dialog.setTitle(TransferSession.verificationPhase(context, progress, stopping));
        Button stop = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        stop.setText(R.string.stop_verification);
        stop.setEnabled(!stopping);
        progressBar.setIndeterminate(progress.totalFiles == 0
                || "retrying".equals(progress.phase) || stopping);
        if (!progressBar.isIndeterminate()) {
            progressBar.setMax(1000);
            progressBar.setProgress((int) Math.min(1000,
                    progress.checkedFiles * 1000.0 / progress.totalFiles));
        }
        progressText.setText(TransferSession.verificationText(context, progress, stopping));
    }

    public void showResult(String result) {
        progressBar.setVisibility(View.GONE);
        progressText.setText(result);
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
