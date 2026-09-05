var executableDirectory = AppContext.BaseDirectory;
const string jarFileName = "HybridFileXfer.jar";
var jarPath = Path.Combine(executableDirectory, jarFileName);

// JDK 相关常量（与父仓库 start.c 保持一致）
const string JdkDirectory = "dragonwell-21.0.5.0.5+9-GA";
const string JdkZipFile = "dragonwell_jdk.zip";
const string JdkUrl = "https://dragonwell.oss-cn-shanghai.aliyuncs.com/21.0.5.0.5%2B9/Alibaba_Dragonwell_Extended_21.0.5.0.5.9_x64_windows.zip";

// 检查 .jar 文件是否存在
if (!File.Exists(jarPath)) {
 Console.Error.WriteLine($"未找到目标文件 {jarFileName}");
 Console.Error.WriteLine($"预期路径：{jarPath}");
 return -1;
}

// 设置控制台编码为 UTF-8，确保中文输出不乱码
Console.OutputEncoding = Encoding.UTF8;
Console.InputEncoding = Encoding.UTF8;

// 1. 检查 Java 环境：优先使用系统 java，否则下载/使用内置 Dragonwell JDK
bool useSystemJava = await CheckJavaEnvironment();

// 2. 列出已连接的 adb 设备
Console.WriteLine("============================");
Console.WriteLine("列出已连接设备：");
RunCommand("adb devices");
Console.WriteLine("============================");

// 3. 选择无线或有线模式
string wirelessIp = "";
string deviceId = "";
Console.Write("请输入 '1' 使用无线模式，直接回车将使用有线模式：");
string? choice = Console.ReadLine()?.Trim() ?? "";

if (choice == "1") {
 Console.Write("请输入无线设备的 IP 地址：");
 wirelessIp = Console.ReadLine()?.Trim() ?? "";
 if (!IsValidIp(wirelessIp)) {
  Console.WriteLine("无效的IP地址,请检查格式");
  WaitAndExit();
  return 1;
 }
} else {
 Console.Write("请输入设备 ID (如果留空，默认使用 -c adb 模式)：");
 deviceId = Console.ReadLine()?.Trim() ?? "";
 if (string.IsNullOrEmpty(deviceId) || deviceId == "none") {
  deviceId = "";
 }
}

// 4. 构建 Java 命令参数
var argList = new List<string> {
 "-Dfile.encoding=UTF-8",
 "-Dsun.stdout.encoding=UTF-8",
 "-Dsun.stderr.encoding=UTF-8",
 "-jar",
 jarPath
};
if (choice == "1") {
 argList.Add("-c");
 argList.Add(wirelessIp);
} else {
 argList.Add("-c");
 argList.Add("adb");
 if (!string.IsNullOrEmpty(deviceId)) {
  argList.Add("-s");
  argList.Add(deviceId);
 }
}

// 5. 启动 java 并实时转发输出
var startInfo = new ProcessStartInfo(useSystemJava ? "java" : Path.Combine(JdkDirectory, "bin", "java.exe")) {
 UseShellExecute = false,
 RedirectStandardOutput = true,
 RedirectStandardError = true,
 CreateNoWindow = true
};
foreach (var arg in argList) {
 startInfo.ArgumentList.Add(arg);
}

Console.WriteLine($"Launching java {string.Join(" ", argList)}\n");

using var process = new Process { StartInfo = startInfo };
process.OutputDataReceived += (_, e) => { if (e.Data != null) Console.WriteLine(e.Data); };
process.ErrorDataReceived += (_, e) => { if (e.Data != null) Console.Error.WriteLine(e.Data); };
process.Start();
process.BeginOutputReadLine();
process.BeginErrorReadLine();
await process.WaitForExitAsync();

// 6. 传输结束，等待按键再关窗
Console.Write("\n按任意键退出...");
Console.ReadLine();
return process.ExitCode;

// ===== 以下为辅助方法 =====

static async Task<bool> CheckJavaEnvironment() {
 // 优先检查系统 java
 try {
  var p = Process.Start(new ProcessStartInfo("java", "-version") {
   UseShellExecute = false,
   RedirectStandardError = true,
   CreateNoWindow = true
  })!;
  p.WaitForExit();
  if (p.ExitCode == 0) {
   Console.WriteLine("检测到系统Java，将使用系统Java执行。");
   return true;
  }
 } catch (Win32Exception) {
  // java 不在 PATH 中，继续检查内置 JDK
 }

 Console.WriteLine("未检测到系统Java，正在检查内置JDK...");

 if (Directory.Exists(JdkDirectory)) {
  Console.WriteLine("内置JDK已存在。");
  return false;
 }

 Console.WriteLine("JDK 文件不存在，正在下载...");
 try {
  using var client = new HttpClient();
  client.Timeout = TimeSpan.FromMinutes(10);
  var data = await client.GetByteArrayAsync(JdkUrl);
  await File.WriteAllBytesAsync(JdkZipFile, data);
  Console.WriteLine($"JDK 文件下载完成：{JdkZipFile}");
 } catch (Exception ex) {
  Console.WriteLine($"下载失败，错误代码: {ex.HResult:X8}");
  Console.WriteLine("请检查网络连接或下载链接是否正确。");
  Console.WriteLine($"下载链接: {JdkUrl}");
  Console.WriteLine($"目标文件: {JdkZipFile}");
  Console.WriteLine("可手动下载文件并将其放置在“dragonwell-21.0.5.0.5+9-GA”目录。");
  WaitAndExit();
  return false;
 }

 // 解压缩 JDK
 Console.WriteLine("正在解压 JDK 文件...");
 var unzipCommand = $"powershell -Command \"Expand-Archive -Path {JdkZipFile} -DestinationPath temp_jdk_extract\"";
 var result = RunCommand(unzipCommand);
 if (result != 0) {
  Console.WriteLine("解压缩失败，请检查系统是否安装了 PowerShell。");
  WaitAndExit();
  return false;
 }
 Console.WriteLine("JDK 文件解压缩完成。");

 // 移动 JDK 目录到正确位置
 Console.WriteLine("正在移动 JDK 文件到正确位置...");
 var moveCommand = $"move temp_jdk_extract\\dragonwell-21.0.5.0.5+9-GA {JdkDirectory}";
 result = RunCommand(moveCommand);
 if (result != 0) {
  Console.WriteLine("移动 JDK 文件失败，请检查系统权限和文件结构。");
  WaitAndExit();
  return false;
 }
 Console.WriteLine("JDK 安装完成。");

 // 清理临时文件
 RunCommand("rmdir /s /q temp_jdk_extract");
 try { File.Delete(JdkZipFile); } catch { }
 return false;
}

static int RunCommand(string command) {
 try {
  var p = Process.Start(new ProcessStartInfo("cmd", "/c " + command) {
   UseShellExecute = false,
   RedirectStandardOutput = true,
   RedirectStandardError = true,
   CreateNoWindow = true
  })!;
  string? line;
  while ((line = p.StandardOutput.ReadLine()) != null) {
   Console.WriteLine(line);
  }
  p.WaitForExit();
  return p.ExitCode;
 } catch (Exception ex) {
  Console.WriteLine($"命令执行失败: {ex.Message}");
  return 1;
 }
}

static bool IsValidIp(string? ip) {
 if (string.IsNullOrEmpty(ip)) return false;
 int dots = 0;
 foreach (var c in ip) {
  if (c == '.') dots++;
  else if (!char.IsDigit(c)) return false;
 }
 if (dots != 3) return false;
 var parts = ip.Split('.');
 if (parts.Length != 4) return false;
 foreach (var part in parts) {
  if (!int.TryParse(part, out var num) || num < 0 || num > 255) return false;
 }
 return true;
}

static void WaitAndExit() {
 Console.Write("按任意键退出...");
 Console.ReadLine();
}