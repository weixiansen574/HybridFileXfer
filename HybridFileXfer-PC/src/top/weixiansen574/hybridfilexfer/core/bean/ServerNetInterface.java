package top.weixiansen574.hybridfilexfer.core.bean;


import org.jetbrains.annotations.NotNull;

import java.net.InetAddress;

public class ServerNetInterface{
    public final String name;
    public final InetAddress address;
    public final InetAddress clientBindAddress;

    public ServerNetInterface(String name, InetAddress address, InetAddress clientBindAddress) {
        this.name = name;
        this.address = address;
        this.clientBindAddress = clientBindAddress;
    }


    @NotNull
    @Override
    public String toString() {
        return "ServerNetInterface{" +
                "name='" + name + '\'' +
                ", address=" + address +
                ", clientBindAddress=" + clientBindAddress +
                '}';
    }
}
