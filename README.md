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

| 参数               | 说明 | 示例 |
|------------------|---|---|
| `--base`         | 服务器监听端口 | `--base=26667` |
| `--dashboard`    | 是否开启 Dashboard 数据统计页面 | `--dashboard=true` |
| `--replicate_to` | 将对战数据转发到指定地址 | `replicate_to=127.0.0.1:3000` |
