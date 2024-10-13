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

import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.bean.TransferEvent;

public class TransferDialog {

    private final Map<String, Holder> holderMap;
    private final Context context;
    private final View dialogView;
    private AlertDialog dialog;

    public TransferDialog(Context context, List<String> channelNames) {
        this.context = context;
        holderMap = new HashMap<>();
        LayoutInflater inflater = LayoutInflater.from(context);
        dialogView = View.inflate(context, R.layout.dialog_transfer, null);
        LinearLayout linearLayout = dialogView.findViewById(R.id.linearLayout);
        for (String channelName : channelNames) {
            View itemView = inflater.inflate(R.layout.item_net_interface_status, linearLayout, false);
            Holder holder = new Holder(itemView);
            holder.channelName.setText(channelName);
            holder.channelIcon.setImageDrawable(ContextCompat.getDrawable(context, Utils.matchIconIdForIName(channelName)));
            holderMap.put(channelName, holder);
            linearLayout.addView(itemView);
        }
    }

    public void show() {
        dialog = new AlertDialog.Builder(context)
                .setTitle("▲0.00MB/s ▼0.00MB/s")
                .setView(dialogView)
                .setPositiveButton("完成", null)
                .setCancelable(false)
                .show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);
    }

    public void showEvent(String iName, String event) {
        Objects.requireNonNull(holderMap.get(iName)).transferEvent.setText(event);
    }

    public void showEvent(TransferEvent event) {
        switch (event.type) {
            case TransferEvent.TYPE_UPLOAD:
                showEvent(event.iName, "▲ " + event.content);
                break;
            case TransferEvent.TYPE_DOWNLOAD:
                showEvent(event.iName, "▼ " + event.content);
                break;
            case TransferEvent.TYPE_UPLOAD_OVER:
                showEvent(event.iName, "上传完成");
                break;
            case TransferEvent.TYPE_DOWNLOAD_OVER:
                showEvent(event.iName, "下载完成");
                break;
            case TransferEvent.TYPE_ERROR:
                showEvent(event.iName,"传输时发生错误："+event.content);
                break;
            case TransferEvent.TYPE_INTERRUPTED:
                showEvent(event.iName,"因其他通道发生了错误，传输已中断");
                break;
        }
    }

    public void showSpeeds(List<TrafficInfo> trafficInfoList) {
        long totalUploadSpeed = 0;
        long totalDownloadSpeed = 0;
        for (TrafficInfo trafficInfo : trafficInfoList) {
            Holder holder = holderMap.get(trafficInfo.iName);
            if (holder != null){
                holder.uploadSpeed.setText(Utils.formatSpeed(trafficInfo.uploadTraffic));
                holder.downloadSpeed.setText(Utils.formatSpeed(trafficInfo.downloadTraffic));
                totalUploadSpeed += trafficInfo.uploadTraffic;
                totalDownloadSpeed += trafficInfo.downloadTraffic;
            }
        }
        dialog.setTitle(String.format(Locale.getDefault(),
                "▲%.2fMB/s ▼%.2fMB/s",
                ((float) totalUploadSpeed) / 1024 / 1024,
                ((float) totalDownloadSpeed) / 1024 / 1024));
    }

    public void setCloseBtnEnable(boolean enable) {
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(enable);
    }

    public void setButton(String text,View.OnClickListener onClickListener){
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
