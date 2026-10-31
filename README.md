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

## 对战结算协议

新客户端在 `SETTLE` 的元数据中上传 `game_mode=NORMAL|SHUFFLE|CLASSIC`，分别表示普通对战、刷牌模式和经典模式，不再上传 `addon_es`（拓展选卡）和 `addon_bp`（平衡调整）。服务器根据模式推导：经典模式两项均关闭，其他模式两项均开启。显式 `game_mode` 优先于旧字段；无效模式返回 `ERR`。

未提供 `game_mode` 的旧客户端继续按原有 `shuffle`、`addon_es`、`addon_bp` 字段解析。数据库保留现有列和历史记录，无需清空或手工迁移；旧记录按已保存的 `game_mode` 归类，不会仅因平衡调整关闭就被改为经典模式。

统计页移除“平衡调整”开关，不提供游戏类型或拓展选卡筛选；继续保留额外卡槽、禁选模式和地图筛选。单卡统计、Top 10 卡组、卡组对局总数、核心卡牌用量及最近对局仅展示 `NORMAL` 普通对战；`SHUFFLE` 刷牌模式和 `CLASSIC` 经典模式只保留结算记录，不参与网页统计。

`/api/card-stats` 和 `/api/top-decks` 固定查询普通对战，不接受 `game_mode` 切换；旧 `balance_patch` 参数和浏览器设置不再影响查询。`mode=DAY|NIGHT|POOL|FOG|ROOF` 仍表示地图。启动时自动建立普通对战统计及最近对局查询所需索引，成功后移除旧开关筛选索引，不删除历史记录。

游戏结算还上传 `netplay_version=<NETPLAY_VERSION>`，直接使用游戏源码中的联机版本常量，不写死版本号。服务器将其保存为整数；首次启动自动为旧数据库补列，未上报版本的旧记录保留为 `0`（未知版本），不猜测历史版本。

网页筛选从左到右为游戏版本、禁选模式、额外卡槽、地图。版本下拉按“最高版本、次高版本、全部版本”排列，取普通对战记录中数值最高的两个不同版本；不足两个时只显示已有版本及全部版本，默认选中“全部版本”。选项随自动刷新更新；当前选择跌出最新两个版本时回到“全部版本”。未知版本和更老的版本仍包含在全部版本的统计中。单卡、卡组、核心卡牌、对局数和最近对局同步使用版本条件，额外卡槽、禁选和地图条件继续生效。

`GET /api/versions` 返回最高两个版本，例如 `{"items":[3212,3211]}`。统计和最近对局 API 接受 `netplay_version=3212` 或 `netplay_version=all`，省略等同全部；非法版本返回 HTTP 400。结算中的显式版本必须为 `1..4294967295` 的十进制整数，非法值返回 `ERR`。部署时先更新服务器，再发布含新上传字段的游戏；旧客户端仍可正常结算。

## 卡牌名称

卡牌 ID 和名称以游戏源码的 `ConstEnums.h`、`Plant.cpp`、`Zombie.cpp` 为准，中文名称对应 `LawnStrings.txt` 和 `AddonStrings.txt`。已补齐选卡范围内的僵尸 ID `102–105`（交通协管、科学家、掘墓、骷髅僵尸）和植物 ID `209–210`（榴莲、红针花）。不加入超出选卡枚举范围的隐藏卡牌：僵尸以 `EXTENDED_NUM_ZOMBIE_SEEDS_IN_CHOOSER` 为上界，新增植物以 `EXTENDED_NUM_SEEDS_IN_CHOOSER` 为上界，均不包含上界值。

服务端只维护卡牌 ID 到英文键名的映射，中文名称继续通过原有机制从 `LawnStrings.txt` 和 `AddonStrings.txt` 中读取，缺少对应条目时显示英文键名。网页按卡牌 ID 解析名称，已有对战记录无需清空或重建。
