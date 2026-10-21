using System.Runtime.InteropServices;
using System.Text;

var baseDirectory = AppContext.BaseDirectory;
var jarPath = Path.Combine(baseDirectory, "HybridFileXfer.jar");
if (!File.Exists(jarPath)) {
    ShowError("HybridFileXfer.jar is missing. Extract the complete distribution before starting.");
    return 1;
}
try {
    bool gui = args.Length == 0 || args.Contains("--gui");
    string executable = OperatingSystem.IsWindows() && gui ? "javaw.exe" : OperatingSystem.IsWindows() ? "java.exe" : "java";
    string bundled = Path.Combine(baseDirectory, "runtime", "bin", executable);
    string? javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
    string installed = javaHome is null ? "" : Path.Combine(javaHome, "bin", executable);
    string java = File.Exists(bundled) ? bundled : File.Exists(installed) ? installed : executable;
    var info = new ProcessStartInfo(java) {
        WorkingDirectory = baseDirectory,
        UseShellExecute = false,
        CreateNoWindow = gui,
        RedirectStandardOutput = gui,
        RedirectStandardError = gui
    };
    info.ArgumentList.Add("-Dfile.encoding=UTF-8");
    info.ArgumentList.Add("-jar");
    info.ArgumentList.Add(jarPath);
    foreach (string arg in args) info.ArgumentList.Add(arg);
    // Do not echo arguments: they may contain a pairing code.
    using var process = new Process { StartInfo = info };
    var error = new StringBuilder();
    if (gui) {
        process.OutputDataReceived += (_, _) => { };
        process.ErrorDataReceived += (_, e) => {
            if (e.Data is not null) lock (error) { if (error.Length < 8192) error.AppendLine(e.Data); }
        };
    }
    process.Start();
    if (gui) { process.BeginOutputReadLine(); process.BeginErrorReadLine(); }
    await process.WaitForExitAsync();
    if (gui && process.ExitCode != 0) {
        string detail;
        lock (error) detail = error.ToString();
        ShowError("The desktop could not start. Install a 64-bit Java runtime, or use the distribution with its bundled runtime.\n\n" + detail);
    }
    return process.ExitCode;
} catch (Exception e) {
    ShowError("Cannot start HybridFileXfer. Install Java or use the bundled desktop distribution.\n\n" + e.Message);
    return 1;
}

static void ShowError(string message) {
    if (OperatingSystem.IsWindows()) NativeMethods.MessageBox(IntPtr.Zero, message, "HybridFileXfer", 0x10);
    else Console.Error.WriteLine(message);
}

internal static class NativeMethods {
    [DllImport("user32.dll", EntryPoint = "MessageBoxW", CharSet = CharSet.Unicode)]
    internal static extern int MessageBox(IntPtr window, string text, string caption, uint type);
}
