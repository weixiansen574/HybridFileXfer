package top.weixiansen574.hybridfilexfer.listadapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import top.weixiansen574.hybridfilexfer.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.R;

public class NetCardsAdapter extends RecyclerView.Adapter<NetCardsAdapter.ViewHolder> {

    private final Context context;
    private List<ItemServerNetInterface> netInterfaceList;
    private boolean enableModify = true;

    public NetCardsAdapter(Context context) throws IOException {
        this.context = context;
        netInterfaceList = getNetInterfaces();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(context).inflate(R.layout.item_net_card_info, parent, false));
    }

    @SuppressLint({"UseCompatLoadingForDrawables", "SetTextI18n"})
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ItemServerNetInterface itemServerNetInterface = netInterfaceList.get(position);
        holder.txvNameAndState.setText(itemServerNetInterface.name + " | "+itemServerNetInterface.state);
        holder.txvIP.setText(itemServerNetInterface.address.getHostAddress());
        String iName = itemServerNetInterface.name;
        boolean enableBindIpInput = !iName.equals("USB_ADB");
        holder.imgInterfaceType.setImageDrawable(context.getDrawable(Utils.matchIconIdForIName(iName)));

        holder.editClientBindIP.setEnabled(enableModify && enableBindIpInput);
        holder.cbEnable.setEnabled(enableModify);
        holder.cbEnable.setChecked(itemServerNetInterface.enable);


        holder.cbEnable.setOnCheckedChangeListener((buttonView, isChecked) -> {
            itemServerNetInterface.enable = isChecked;
        });

        holder.editClientBindIP.setText(itemServerNetInterface.clientBindAddress);

        holder.editClientBindIP.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                itemServerNetInterface.clientBindAddress = s.toString();
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

    }

    @Override
    public int getItemCount() {
        return netInterfaceList.size();
    }

    public void changeItemState(String name,String state){
        for (int i = 0; i < netInterfaceList.size(); i++) {
            ItemServerNetInterface item = netInterfaceList.get(i);
            if (item.name.equals(name)){
                item.state = state;
                notifyItemChanged(i);
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        return position;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }



    @SuppressLint("NotifyDataSetChanged")
    public void setEnableModify(boolean enableModify) {
        this.enableModify = enableModify;
        if (enableModify){
            for (ItemServerNetInterface item : netInterfaceList) {
                item.state = context.getString(R.string.not_run);
            }
        }
        notifyDataSetChanged();
    }

    public List<ServerNetInterface> getSelectedInterfaces() {
        List<ServerNetInterface> serverNetInterfaceList = new ArrayList<>();
        for (ItemServerNetInterface netInterface : netInterfaceList) {
            if (netInterface.enable) {
                try {
                    serverNetInterfaceList.add(netInterface.toServerNetInterface());
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(context, context.getString(R.string.Invalid_ip) + netInterface.clientBindAddress, Toast.LENGTH_SHORT).show();
                    return null;
                }
            }
        }
        return serverNetInterfaceList;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void reload() throws SocketException, UnknownHostException {
        netInterfaceList = getNetInterfaces();
        notifyDataSetChanged();
    }


    public static class ViewHolder extends RecyclerView.ViewHolder {
        CheckBox cbEnable;
        TextView txvNameAndState;
        TextView txvIP;
        EditText editClientBindIP;
        ImageView imgInterfaceType;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            cbEnable = itemView.findViewById(R.id.cb_interface_enable);
            txvNameAndState = itemView.findViewById(R.id.txv_interface_name_and_state);
            txvIP = itemView.findViewById(R.id.txv_interface_ip);
            editClientBindIP = itemView.findViewById(R.id.edit_client_bind_ip);
            imgInterfaceType = itemView.findViewById(R.id.img_interface_type);
        }
    }

    public static class ItemServerNetInterface {

        boolean enable = true;
        String name;
        InetAddress address;
        String clientBindAddress = "";
        String state;

        public ItemServerNetInterface(String name, InetAddress address,String state) {
            this.name = name;
            this.state = state;
            this.address = address;
        }

        public ServerNetInterface toServerNetInterface() throws UnknownHostException {
            return new ServerNetInterface(name, address, TextUtils.isEmpty(clientBindAddress) ? null :
                    InetAddress.getByName(clientBindAddress));
        }
    }

    private ArrayList<ItemServerNetInterface> getNetInterfaces() throws SocketException, UnknownHostException {
        ArrayList<ItemServerNetInterface> netInterfaceList = new ArrayList<>();
        netInterfaceList.add(new ItemServerNetInterface("USB_ADB", InetAddress.getByName("127.0.0.1"),context.getString(R.string.not_run)));
        Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
        while (networkInterfaces.hasMoreElements()) {
            NetworkInterface networkInterface = networkInterfaces.nextElement();
            //System.out.println(networkInterface.getDisplayName());
            Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
            while (inetAddresses.hasMoreElements()) {
                InetAddress address = inetAddresses.nextElement();
                if (!address.isLoopbackAddress() && address instanceof Inet4Address
                        && !networkInterface.getDisplayName().startsWith("rmnet_data"/*数据流量除外*/)) {
                    ItemServerNetInterface item = new ItemServerNetInterface(networkInterface.getDisplayName(), address, context.getString(R.string.not_run));
                    //VPN开的虚拟网卡，默认不勾选
                    if (item.name.startsWith("tun")){
                        item.enable = false;
                    }
                    netInterfaceList.add(item);
                    System.out.println(networkInterface.getDisplayName() + "  " + address);
                }
            }
        }
        return netInterfaceList;
    }


}
