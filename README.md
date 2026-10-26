# PvZ-TV-Server

## 项目简介

`PvZ-TV-Server` 是用于 **植物大战僵尸 TV 触控版** 的联机服务器。

除了基础联机功能之外，还支持以下功能：

- 开启 Dashboard 对战数据统计网页（可展示本服务器收集到的对战数据）
- 将本服务器收集到的对战数据转发到指定服务器（用于汇总数据到同一个服务器）

## 编译方式

使用 IntelliJ IDEA 编译：


1. 打开项目
2. 在右侧打开 **Maven** 面板
3. 展开 **Lifecycle** ( **生存期** )
4. 双击执行：

```bash
package
```

编译完成后，会生成：

```bash
PvZ-TV-Server.jar
```

## 运行方式

### 启动服务器

启动服务器，并指定端口为 `26667`：

```bash
java -jar PvZ-TV-Server.jar --base=26667
```

### 启动服务器并开启 Dashboard

启动服务器，端口为 `26667`，同时开启 Dashboard 对战数据统计网页：

```bash
java -jar PvZ-TV-Server.jar --base=26667 --dashboard=true
```

### 启动服务器并转发对战数据

启动服务器，端口为 `26667`，同时将对战数据转发到指定的 `IP:PORT`：

```bash
java -jar PvZ-TV-Server.jar --base=26667 --replicate_to=IP:PORT
```


## 参数说明

长期运行的内存保护、自动清理规则及 Linux 故障自动重启配置见 [部署说明](deploy/README.md)。systemd 模板位于 [deploy/pvz-tv-server.service](deploy/pvz-tv-server.service)，JVM 和服务器参数位于 [deploy/pvz-tv-server.env](deploy/pvz-tv-server.env)。

| 参数               | 说明 | 示例                              |
|------------------|---|---------------------------------|
| `--base`         | 服务器监听端口 | `--base=26667`                  |
| `--dashboard`    | 是否开启 Dashboard 数据统计页面 | `--dashboard=true`              |
| `--replicate_to` | 将对战数据转发到指定地址 | `--replicate_to=127.0.0.1:3000` |

## 卡牌名称

卡牌 ID 和名称以游戏源码的 `ConstEnums.h`、`Plant.cpp`、`Zombie.cpp` 为准，中文名称对应 `LawnStrings.txt` 和 `AddonStrings.txt`。本次只补齐选卡范围内的僵尸 ID `102–104`（交通协管、科学家、掘墓僵尸）和植物 ID `209–210`（榴莲、红针花）。不加入超出选卡枚举范围的隐藏卡牌：僵尸以 `NUM_ZOMBIE_SEEDS_IN_CHOOSER` 为上界，新增植物以 `NUM_SEEDS_IN_CHOOSER_EXTENDED` 为上界，均不包含上界值。

服务端只维护卡牌 ID 到英文键名的映射，中文名称继续通过原有机制从 `LawnStrings.txt` 和 `AddonStrings.txt` 中读取，缺少对应条目时显示英文键名。网页按卡牌 ID 解析名称，已有对战记录无需清空或重建。
