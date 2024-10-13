package top.weixiansen574.hybirdfilexfer;

import top.weixiansen574.hybirdfilexfer.core.HFXClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws IOException {
        Map<String, String> paramMap = new HashMap<>();
        parseArguments(paramMap, args);
        String connect = paramMap.get("-c");
        if (connect == null) {
            System.out.println("未指定控制通道连接方式\n参数说明：");
            System.out.println("-c 控制通道连接方式 \"adb\" 或 网络ip");
            System.out.println("-s adb连接方式下指定的设备（adb有多设备的情况），你可以用\"adb devices\"命令查看设备");
            System.out.println("示例：");
            System.out.println("-c adb");
            System.out.println("-c adb -s abcd1234");
            System.out.println("-c 192.168.1.2");
        } else if (connect.equals("adb")) {
            if (executeAdbForwardCommand(5740, paramMap.get("-s"))) {
                System.out.println("USB_ADB : 5740 端口转发成功！");
                HFXClient hfxClient = new HFXClient("127.0.0.1", 5740);
                if (hfxClient.connect()) {
                    hfxClient.start();
                }
            }
        } else {
            HFXClient hfxClient = new HFXClient(connect, 5740);
            if (hfxClient.connect()) {
                hfxClient.start();
            }
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
            StringBuilder adbCommand = new StringBuilder();
            adbCommand.append("./adb");
            if (device != null) {
                adbCommand.append(" -s ").append(device);
            }
            adbCommand.append(" forward tcp:");
            adbCommand.append(port);
            adbCommand.append(" tcp:");
            adbCommand.append(port);

            // 构建adb forward命令
            String adbForwardCommand = adbCommand.toString();

            // 执行adb forward命令
            Process process = Runtime.getRuntime().exec(adbForwardCommand, null, new java.io.File(jarDirectory));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            //BufferedReader errorReader = process.errorReader();
            String l;
            while ((l = errorReader.readLine()) != null) {
                System.err.println(l);
            }
            // 读取adb forward命令执行的输出
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("adb:" + line);
            }

            // 等待命令执行完成
            int exitCode = process.waitFor();
            return exitCode == 0;

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false; // 执行失败
        }
    }

}
