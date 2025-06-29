var executableDirectory = AppContext.BaseDirectory;
const string jarFileName = "HybridFileXfer.jar";
var jarPath = Path.Combine(executableDirectory, jarFileName);

// 检查 .jar 文件是否存在
if (!File.Exists(jarPath)) {
	Console.Error.WriteLine($"未找到目标文件 {jarFileName}");
	Console.Error.WriteLine($"预期路径：{jarPath}");
	return -1;
}

try {
	// 配置进程启动信息
	var startInfo = new ProcessStartInfo("java") {
		UseShellExecute = false,
		RedirectStandardOutput = true,
		RedirectStandardError = true,
		CreateNoWindow = true
	};

	// 添加启动参数
	startInfo.ArgumentList.Add("-jar");
	startInfo.ArgumentList.Add(jarPath);
	foreach (var arg in args) {
		startInfo.ArgumentList.Add(arg);
	}

	// 输出启动信息，并额外空行
	Console.WriteLine($"Launching java {string.Join(" ", startInfo.ArgumentList)}\n");

	using var process = new Process { StartInfo = startInfo };

	// 以异步方式实时接收和打印输出
	process.OutputDataReceived += static (_, e) => { if (e.Data != null) { Console.WriteLine(e.Data); } };
	process.ErrorDataReceived += static (_, e) => { if (e.Data != null) { Console.Error.WriteLine(e.Data); } };

	// 启动进程并开始读取输出和错误流
	process.Start();
	process.BeginOutputReadLine();
	process.BeginErrorReadLine();

	// 等待进程退出
	await process.WaitForExitAsync();

	// 传递退出码
	return process.ExitCode;
} catch (Win32Exception e) when (e.NativeErrorCode == 2) {
	Console.Error.WriteLine("未能启动 Java 进程");
	Console.Error.WriteLine("请确保 java 已正确安装，并且其路径已添加到系统的 PATH 环境变量中");
	Console.WriteLine(e.Message);
	return -1;
} catch (Exception e) {
	Console.Error.WriteLine("未能启动 Java 进程");
	Console.Error.WriteLine($"启动器发生未知错误");
	Console.WriteLine(e.Message);
	return -1;
}