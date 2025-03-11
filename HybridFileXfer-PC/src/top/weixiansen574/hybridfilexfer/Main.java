package top.weixiansen574.hybridfilexfer;

import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkHFXClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {
    private static final ConnectServerCallback connectServerCallback = new ConnectServerCallback() {
        @Override
        public void onConnectingControlChannel(String address, int port) {
            Strings.printf("connecting_control_channel", address);
        }

        @Override
        public void onVersionMismatch(int localVersion, int remoteVersion) {
            Strings.printf("version_mismatch", localVersion, remoteVersion);
        }

        @Override
        public void onConnectControlFailed() {
            Strings.printf("connect_control_failed");
        }

        @Override
        public void onConnectingTransferChannel(String name, InetAddress inetAddress, InetAddress bindAddress) {
            Strings.printf("connecting_transfer_channel", name, inetAddress.getHostAddress(),
                    bindAddress == null ? "null" : bindAddress.getHostAddress());
        }

        @Override
        public void onConnectTransferChannelFailed(String name, InetAddress inetAddress, Exception e) {
            Strings.printf("connect_transfer_failed", name, inetAddress.getHostAddress(), e.toString());
        }

        @Override
        public void onOOM(int createdBuffers, int requiredBuffers, long maxMemoryMB, String osArch) {
            Strings.printf("out_of_memory", createdBuffers, requiredBuffers, maxMemoryMB);
            if (osArch != null && !osArch.contains("64")) {
                Strings.printf("java_32bit_warning");
            }
        }

        @Override
        public void onRemoteOOM() {
            Strings.printf("remote_out_of_memory");
        }

        @Override
        public void onConnectSuccess(List<String> channelNames) {
            Strings.printf("connect_success");
        }
    };

    private static final ClientCallBack clientCallBack = new ClientCallBack() {
        @Override
        public void onReceiving() {
            Strings.printf("receiving_files");
        }

        @Override
        public void onSending() {
            Strings.printf("sending_files");
        }

        @Override
        public void onExit() {
            Strings.printf("client_exit");
        }

        @Override
        public void onFileUploading(String iName, String path, long targetSize, long totalSize) {

        }

        @Override
        public void onFileDownloading(String iName, String path, long targetSize, long totalSize) {

        }

        @Override
        public void onSpeedInfo(List<TrafficInfo> trafficInfoList) {

        }

        @Override
        public void onChannelComplete(String iName, long traffic, long time) {
            Strings.printf("channel_complete", iName, time == 0 ? "∞" : Utils.formatSpeed(traffic / time * 1000));
        }

        @Override
        public void onChannelError(String iName, int errorType, String message) {
            switch (errorType) {
                case TransferFileCallback.ERROR_TYPE_EXCEPTION:
                    Strings.printf("channel_error_exception", iName, message);
                    break;
                case TransferFileCallback.ERROR_TYPE_INTERRUPT:
                    Strings.printf("channel_error_interrupt", iName);
                    break;
                case TransferFileCallback.ERROR_TYPE_READ_ERROR:
                    Strings.printf("channel_error_read", iName);
                    break;
                case TransferFileCallback.ERROR_TYPE_WRITE_ERROR:
                    Strings.printf("channel_error_write", iName);
                    break;
            }
        }

        @Override
        public void onReadFileError(String message) {
            Strings.printf("file_read_error", message);
        }

        @Override
        public void onWriteFileError(String message) {
            Strings.printf("file_write_error", message);
        }

        @Override
        public void onComplete(boolean isUpload, long traffic, long time) {
            if (isUpload) {
                Strings.printf("upload_complete", Utils.formatSpeed(traffic / time * 1000), Utils.formatTime(time), Utils.formatFileSize(traffic));
            } else {
                Strings.printf("download_complete", Utils.formatSpeed(traffic / time * 1000), Utils.formatTime(time), Utils.formatFileSize(traffic));
            }
        }

        @Override
        public void onIncomplete() {
            Strings.printf("transfer_failed");
        }
    };


    public static void main(String[] args) throws Exception {
        Map<String, String> paramMap = new HashMap<>();
        parseArguments(paramMap, args);
        String connect = paramMap.get("-c");
        String homeDir = paramMap.get("-d");
        if (homeDir == null) {
            homeDir = "/";
        }

        String serverAddress;

        if (connect == null) {
            /*
            未指定控制通道连接方式
            参数说明：
            -c 控制通道连接方式 "adb" 或 网络ip
            -s adb连接方式下指定的设备（adb有多设备的情况），你可以用"adb devices"命令查看设备
            示例：
            -c adb
            -c adb -s abcd1234
            -c 192.168.1.2
            */
            System.out.println(Strings.get("usage"));
            return;
        } else if (connect.equals("adb")) {
            if (executeAdbForwardCommand(5740, paramMap.get("-s"))) {
                System.out.println(Strings.get("adb_forward_succeed"));
                serverAddress = "127.0.0.1";
            } else {
                return;
            }
        } else {
            serverAddress = connect;
        }

        JdkHFXClient hfxClient = new JdkHFXClient(serverAddress, 5740, homeDir);
        if (hfxClient.connect(connectServerCallback)) {
            hfxClient.start(clientCallBack);
        }
    }

    private static void parseArguments(Map<String, String> paramMap, String[] args) {
        // 遍历数组并解析参数
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-") && i + 1 < args.length && !args[i + 1].startsWith("-")) {
                paramMap.put(args[i], args[i + 1]);
                i++; // 跳过值
            }
        }
    }

    public static boolean executeAdbForwardCommand(int port, String device) {
        try {
            // 获取当前jar包所在的目录
            String jarDirectory = System.getProperty("user.dir");

            // 执行 adb version 命令来检查 adb 是否在环境变量中
            Process process = Runtime.getRuntime().exec("adb version");
            int exitCode = process.waitFor();

            // 如果 adb version 执行成功，则说明 adb 已在环境变量中
            String adbPath;
            if (exitCode == 0) {
                adbPath = "adb"; // 环境变量中找到adb
            } else {
                adbPath = "./adb"; // 如果没有找到adb，使用当前目录下的 adb
            }

            // 构建adb forward命令
            StringBuilder adbCommand = new StringBuilder();
            adbCommand.append(adbPath);

            if (device != null) {
                adbCommand.append(" -s ").append(device);
            }
            adbCommand.append(" forward tcp:");
            adbCommand.append(port);
            adbCommand.append(" tcp:");
            adbCommand.append(port);

            // 执行adb forward命令
            Process forwardProcess = Runtime.getRuntime().exec(adbCommand.toString(), null, new File(jarDirectory));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(forwardProcess.getErrorStream()));
            String l;
            while ((l = errorReader.readLine()) != null) {
                System.err.println(l);
            }

            // 读取adb forward命令执行的输出
            BufferedReader reader = new BufferedReader(new InputStreamReader(forwardProcess.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("adb:" + line);
            }

            // 等待命令执行完成
            int forwardExitCode = forwardProcess.waitFor();
            return forwardExitCode == 0;

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false; // 执行失败
        }
    }

}
