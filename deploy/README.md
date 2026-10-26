# 内存保护与 systemd 部署

服务器正常运行时自动清理，不需要设置固定周期重启。systemd 用于在 JVM 内存溢出、关键线程退出、主循环停滞或进程被操作系统终止后恢复服务。单实例重启会断开当前对局。

## 本版本的保护

| 资源 | 默认限制与清理方式 |
| --- | --- |
| 游戏连接 | 单 IP 24 个；全局默认 `min(1024, 最大堆 / 512 KiB)`，最低 16 个；断开释放名额 |
| IP 防滥用记录 | 最多 32768 个；每分钟清理一次，无连接、限流窗口已结束且空闲 10 分钟的记录会删除；表满时拒绝新 IP，保留现有连接的计数 |
| 发送缓冲 | 单连接最多 1 MiB、4096 个缓冲；全局最多 `min(64 MiB, 最大堆 / 8)`，最低 1 MiB，以及 65536 个缓冲；部分写出仍计入完整数组，写完或断开才释放预算 |
| 慢客户端 | 超过发送预算后关闭；即使上层忽略发送异常，也会在主循环下一次检查时清理 |
| 控制消息 | 单连接每次读取最多处理 128 条控制命令，每个 1 秒窗口最多 256 条，超限关闭异常连接；已建立的原始中继对战数据不受该限制 |
| P2P 确认 | 仅在 P2P 协商阶段接受，每一端每轮只记录首次确认；中继阶段和重复确认不会重复写日志，重复开始命令不会重置对局状态 |
| 空闲连接 / 房间 | 保留原有 10 分钟无输入处理，以及每分钟清理创建已满 20 分钟、尚未开局的房间；发送通知不再刷新客户端活跃时间 |
| 结算输入 | 单条最多 64 KiB（UTF-8 字节，包括可选的 CR，不包括 LF），最多 512 条卡牌事件；每次连接总期限 10 秒，包括读取和响应；超限关闭连接 |
| 统计转发 | 最多 4096 条，估算占用最多 `min(16 MiB, 最大堆 / 32)`，最低 256 KiB；此外最多一条正在发送或等待重试的有界报文；满时丢弃新转发项并记录累计数量 |
| 网页服务 | 默认最多 128 个 HTTP 连接，4～16 个工作线程、128 个排队请求；请求期限 10 秒、响应期限 30 秒；不再持有浏览器长连接，EventSource 每 15 秒自动重连刷新 |
| 统计结果 | 内存摘要最多保留 4096 种卡牌 ID；网页卡牌查询最多返回 4096 行；数据库仍保留已接受的结算 |
| 故障恢复 | 未捕获异常导致进程非零退出；主循环超过 90 秒没有完成一轮，独立看门狗使进程非零退出，由 systemd 重启 |

转发队列仍是内存队列：队列溢出或进程重启时，尚未发出的记录不会自动补传；本机 SQLite 已提交的数据保留。没有新增按时间删除历史对战数据的行为。

每分钟的 `[MEMORY]` 日志包含堆占用、连接数、历史 IP 数、释放的 IP 数、发送缓冲占用和转发队列占用。堆占用含尚未回收的垃圾，不能把某次高值直接视为泄漏；结合 GC 日志观察回收后的趋势。

可在 `JAVA_OPTS` 中追加以下参数（放在 `-jar` 前），设置的整数必须大于零：

```text
-Dpvz.maxConnections=1024
-Dpvz.maxTrackedIps=32768
-Dpvz.maxQueuedBytes=67108864
-Dpvz.maxReplicationBytes=16777216
-Dpvz.loopTimeoutSeconds=90
```

看门狗最短允许 5 秒，生产环境建议保留 90 秒，避免短暂 GC 或宿主机调度延迟触发重启。不要只提高连接/队列上限而不评估 `-Xmx` 和机器内存。无需定时调用 `System.gc()`。

## 安装

使用维护中的 OpenJDK / HotSpot 17 或更新版本，以及 systemd 245 或更新版本。HTTP 连接和超时限制使用 JDK 内置 HttpServer 的配置项，建议更新到相应 JDK 的最新维护版本。此模板的 `-Xmx512m` 和 `MemoryMax=1G` 是示例，需为操作系统及其他服务预留内存。

在项目目录编译：

```bash
mvn package
```

在 Linux 服务器上，从已上传的项目目录执行以下命令。已有同名服务时，应先安排维护窗口停止旧实例，避免端口冲突。

```bash
id pvz-server >/dev/null 2>&1 || sudo useradd --system --user-group --home-dir /var/lib/pvz-tv-server --shell /usr/sbin/nologin pvz-server
sudo install -d -m 0755 /opt/pvz-tv-server
sudo install -d -o pvz-server -g pvz-server -m 0750 /var/lib/pvz-tv-server /var/log/pvz-tv-server
sudo install -m 0644 target/PvZ-TV-server.jar /opt/pvz-tv-server/PvZ-TV-server.jar
sudo install -m 0644 deploy/pvz-tv-server.service /etc/systemd/system/pvz-tv-server.service
sudo install -m 0644 deploy/pvz-tv-server.env /etc/pvz-tv-server.env
```

**迁移原有数据后再启动。** 程序相对工作目录读取 `data/`，新位置为 `/var/lib/pvz-tv-server/data/`。停止旧实例后，将其原工作目录的整个 `data/` 复制到这里，包括 `pvz_metrics.db`、仍存在的 `-wal` / `-shm` 文件及文本资源；先保留备份，确保归属用户为 `pvz-server`。不要在旧进程仍写数据库时只复制单个 `.db` 文件。

编辑 `/etc/pvz-tv-server.env`，保留原端口及需要的 `--replicate_to` 配置；确认 `/usr/bin/java` 是所需 JDK。改变 `-Xmx` 时也应调整 service 的 `MemoryMax`，给线程栈、直接内存、SQLite 等堆外开销留余量。

```bash
sudo systemd-analyze verify /etc/systemd/system/pvz-tv-server.service
sudo systemctl daemon-reload
sudo systemctl enable --now pvz-tv-server
sudo systemctl status pvz-tv-server
sudo journalctl -u pvz-tv-server -f
```

模板端口为游戏 `26667–26670`、探测 `27667–27674`、统计 `29667–29670`、网页 `30667`，均为 TCP。防火墙按实际功能开放。当前探测端口布局支持 `--shards=1..4`。

`Restart=on-failure` 在失败后等待 5 秒重启，5 分钟内最多允许启动 5 次，防止错误配置造成无限重启。达到上限后，修复问题并运行：

```bash
sudo systemctl reset-failed pvz-tv-server
sudo systemctl start pvz-tv-server
```

主动 `systemctl stop` 不会触发自动重启。模板没有设置 `WatchdogSec`，因为应用没有实现 systemd 的 `sd_notify` 协议；主循环检测由应用内看门狗完成。

## 故障诊断与验证

- 堆溢出时，JVM 尝试写入 `/var/log/pvz-tv-server/heapdump.hprof`，随后退出；转储需要时间，systemd 在进程实际退出后才会重启。操作系统 OOM kill 不保证生成 Java 堆转储。
- 堆转储文件名固定，已有文件不会覆盖。保存分析完成后将它移走或删除，才可记录下一次故障；预留至少一个最大堆大小的磁盘空间。
- GC 日志位于 `/var/log/pvz-tv-server/gc.log*`，配置按 10 MiB、5 个文件轮换；应用输出进入 journal，容量遵循服务器的 journald 设置。
- 检查 `systemctl show pvz-tv-server -p NRestarts -p ExecMainStatus` 和 `[MEMORY]` 日志；健康验证应实际发出游戏查询请求并确认响应，不能仅看 PID 或 TCP 端口是否存在。
- 若旧版使用 `nohup ... > server.log 2>&1 &`，单个文件不会自动轮转。出现大量重复 `P2P_OK` 日志时，先停止旧进程、保留日志末尾样本，再清空原日志并换用修复版；截断前确认样本已保存，截断会丢弃其余日志。不要在旧进程仍以非追加方式写入时直接截断，以免后续写入保留原偏移而生成稀疏文件。推荐迁移到上面的 systemd/journal 部署方式。
- 可在测试实例上用 `systemctl kill --signal=SIGKILL pvz-tv-server` 验证故障重启；该操作会立即中断连接，不要对正在承载对局的生产实例执行。

本地测试覆盖报文限制、慢速输入期限、连接与 IP 淘汰、队列预算及断开回收，并在独立子进程验证故障退出。部署后仍应观察实际并发下 GC 后的堆占用；本次原始故障没有堆转储，不能据此宣称已定位唯一根因。

参考：[systemd.service](https://www.freedesktop.org/software/systemd/man/latest/systemd.service.html)、[JVM 内存诊断](https://docs.oracle.com/en/java/javase/17/troubleshoot/troubleshooting-memory-leaks.html)、[OpenJDK HttpServer 配置](https://github.com/openjdk/jdk17u/blob/master/src/jdk.httpserver/share/classes/sun/net/httpserver/ServerConfig.java)。
