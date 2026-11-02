# 本机传输与校验基准测试

仓库提供了两个独立的基准测试工具，用于在开发过程中评估加密通道、分块 CRC32 校验、断点检查点以及可选全文件 SHA-256 校验的本机软件开销。基准工具不会随常规单元测试自动执行。

---

## 一、本机回环传输基准（`tools/benchmark_transfer.py`）

先编译桌面端与测试类，再运行基准脚本：

```bash
python tools/build_desktop.py --test

# 默认配置：1 个 64 MiB 大文件 + 200 个 16 KiB 小文件，分别测试 1/2/4 通道，重复 3 轮
python tools/benchmark_transfer.py --size-mib 64 --small-files 200 --small-kib 16 --channels 1,2,4 --repetitions 3

# 开启传输后双端 SHA-256 全文件校验并对比耗时
python tools/benchmark_transfer.py --size-mib 64 --small-files 200 --small-kib 16 --channels 1,2,4 --repetitions 3 --sha256 --output .build/benchmarks/transfer-sha256.csv
```

- **测试流程**：脚本会在临时目录生成随机测试文件，通过本机回环（Loopback）加密通道完成完整传输，并在结束后自动清理临时目录。若系统默认 `java` 版本不匹配，可通过 `JAVA_HOME` 指定 JDK。
- **统计范围**：默认记录从开始传输、CRC32 分块校验、检查点写入到最终文件提交的端到端耗时；传入 `--sha256` 时还会包含传输后双端并发计算 SHA-256 与比对的耗时。结果默认输出至 `.build/benchmarks/transfer.csv`。
- **顺序偏差控制**：偶数轮次会自动反转通道数测试顺序（如 `1,2,4` 与 `4,2,1` 交替），以减轻操作系统页面缓存预热带来的先后顺序偏差。
- **适用场景**：回环测试主要反映协议栈、AES-GCM 加解密与本机磁盘的软件开销；评估真实手机传输性能时，仍需在实际设备上结合具体的 USB / Wi-Fi 链路和存储介质进行实测。

---

## 二、CRC32 缓冲区微基准（`BlockChecksumBenchmark.java`）

`tools/java/BlockChecksumBenchmark.java` 用于单独测量在协议既有的 1 MiB 堆内存缓冲区（64 MiB 工作集）上计算 CRC32 的纯 CPU / 内存带宽开销，不包含文件读写、加解密或网络 I/O：

```bash
javac --release 8 -encoding UTF-8 -d .build/benchmark-classes tools/java/BlockChecksumBenchmark.java
java -cp .build/benchmark-classes BlockChecksumBenchmark
```
