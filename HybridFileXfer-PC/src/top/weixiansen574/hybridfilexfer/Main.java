/**
 * This program is free software: redistribute it and/or modify it under GPLv3+.
 * See <https://www.gnu.org/licenses/> for full license details.
 */
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

    /**主函数*/
    public static void main(String[] args) throws Exception {
        Map<String, String> paramMap = new HashMap<>();
        parseArguments(paramMap, args);

        // 打印版本号
        if (paramMap.containsKey("-v")) {
            Strings.printf("version");
            return;
        }

        // 打印帮助
        if (paramMap.containsKey("-h")) {
            Strings.printf("man_page");
            return;
        }

        // 解析命令行参数
        final String connect = paramMap.get("-c");
        String homeDir = paramMap.get("-s");  //因为会修改为默认位置，所以不可以设置为不可变变量
        final String device = paramMap.get("-d");

        // 指定默认目录
        if (homeDir == null) {
            homeDir = "/";
        }

        // 未指定连接方式时显示 man Page
        if (connect == null || connect.isEmpty()) {
            System.err.println(Strings.get("control_chanel_null"));
            Strings.printf("see_help");
            return;
        }

        // 设置服务器地址
        final String serverAddress;
        if (connect.equals("adb")) {
            if (executeAdbForwardCommand(5740, device)) {
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

    /**
     * 将所有命令行标签转化为 String 键值对
     * @param paramMap 参数键值对
     * @param args 所有命令行标签
     * @author nlsdt 2025-7-10
     */
    private static void parseArguments(Map<String, String> paramMap, String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            // 处理参数
            if (arg.startsWith("-")) {


                String value = null;
                String key = arg;

                if (arg.contains("=")) {
                    final int eqPos = arg.indexOf('=');
                    if (eqPos < arg.length() - 1) {
                        value = arg.substring(eqPos + 1);
                    }
                    arg = arg.substring(0, eqPos);
                }
                // 检查下一个参数是否是值
                else if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    value = args[i + 1];
                    i++;
                }

                switch (key){
                    case "--connect":       key = "-c"; break;
                    case "--switch-device": key = "-s"; break;
                    case "--dir":           key = "-d"; break;
                    case "--help":          key = "-h"; break;
                    case "--version":       key = "-v"; break;
                    case "-c":
                    case "-s":
                    case "-d":
                    case "-h":
                    case "-v":
                        break;
                    default: // 非法参数处理
                        if (arg.startsWith("-")) {
                            Strings.printf("unknown_option", key);
                            Strings.printf("see_help");
                        } else {
                            Strings.printf("invalid_argument", key);
                            Strings.printf("see_help");

                        }
                }

                paramMap.put(key, value);
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
