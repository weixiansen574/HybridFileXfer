package top.weixiansen574.hybridfilexfer;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;

//TODO 国际化
public class TransferDialog {
    public final boolean isUpload;
    private final Map<String, Holder> holderMap;
    private final Context context;
    private final View dialogView;
    private AlertDialog dialog;


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
                itemView.findViewById(R.id.download_speed_group).setVisibility(View.GONE);
            } else {
                itemView.findViewById(R.id.upload_speed_group).setVisibility(View.GONE);
            }
            Holder holder = new Holder(itemView);
            holder.channelName.setText(channelName);
            holder.channelIcon.setImageDrawable(ContextCompat.getDrawable(context, Utils.matchIconIdForIName(channelName)));
            holderMap.put(channelName, holder);
            linearLayout.addView(itemView);
        }
        this.isUpload = isUpload;
    }



    public void show() {
        dialog = new AlertDialog.Builder(context)
                .setTitle(isUpload ? "0.00MB/s · 发送中" : "0.00MB/s · 接收中")
                .setView(dialogView)
                .setPositiveButton("完成", null)
                .setCancelable(false)
                .show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);
    }

    public void showEvent(String iName, String event) {
        Objects.requireNonNull(holderMap.get(iName)).transferEvent.setText(event);
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
            dialog.setTitle(String.format(Locale.getDefault(), "%.2fMB/s · 发送中",
                    ((float) totalUploadSpeed) / 1024 / 1024));
        } else {
            dialog.setTitle(String.format(Locale.getDefault(), "%.2fMB/s · 接收中",
                    ((float) totalDownloadSpeed) / 1024 / 1024));
        }
    }

    public void complete(long traffic, long time){
        setCloseBtnEnable(true);
        dialog.setTitle((isUpload ? "▲ " : "▼ ") + Utils.formatSpeed(traffic / time * 1000) +
                " · "+Utils.formatTime(time));
        for (Map.Entry<String, Holder> entry : holderMap.entrySet()) {
            Holder holder = entry.getValue();
            holder.downloadSpeed.setText(Utils.formatSpeed(0));
            holder.uploadSpeed.setText(Utils.formatSpeed(0));
        }
    }
    public void setTitle(String title){
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
