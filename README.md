# MambaDrone

让 AI Agent 用一句话指挥一台大疆无人机：起飞、点动、环绕拍照、看实时画面、录像。

第一台实验机是闲鱼 495 元收的初代 DJI Mavic Mini（御 Mini）。指令由 AI Agent（Claude Opus 5.5，经 [MambaBot](https://github.com/guangxiangdebizi/mambabot-oss)）在云端服务器上发出，经中转服务到手机 App，再由手机通过遥控器发给飞机。

```
AI Agent ──CLI──▶ relay.py（云端，127.0.0.1:8793）
                     │  WebSocket /drone/ws（nginx 反代，token 鉴权）
                     ▼
              安卓 App（DJI Mobile SDK v4.18）──USB──▶ 遥控器 ──▶ 飞机
                     │
                     └─ 实时画面 JPEG / 照片 / 原始视频流 ──▶ relay.py /drone/upload
```

## 目录

| 目录 | 内容 |
|---|---|
| `android/` | Kotlin 安卓 App。虚拟摇杆飞控、任务（环绕 / 网格 / 点动）、DJI 诊断码读取、图传软件解码、原始流录像 |
| `relay/relay.py` | aiohttp 中转。公网侧给手机 App 用，本地侧给 Agent 用 |
| `cli/drone` | 给 Agent（或人）用的命令行 |

## 命令

```
drone status                      链路 + 遥测
drone diag                        DJI 诊断码、激活状态、传感器状态（起飞被拒时先看这个）
drone takeoff | land | rth | stop
drone nudge fwd=0.3 right=0 up=0 yaw=0 secs=1   不依赖 GPS 的机体系点动，|v|≤0.5 m/s，≤2 s
drone move fwd=2 right=0 up=1     GPS 闭环移动
drone orbit radius=6 photos=24    环绕拍照（用于 3D 重建）
drone grid ...                    网格航拍
drone look                        最新一帧实时画面（手机端解码后传 JPEG）
drone record on|off               把图传原始流录到手机
drone sim on|off / simtest        DJI 模拟器
drone download mission=<id>       从 SD 卡取回某次任务的原图
```

## 两个踩过的坑

**1. 起飞返回 `Undefined Error`。** 二手机可能从来没激活过。`drone diag` 会读出 `8012: 设备未激活`：用 DJI Fly 连一次飞机、点「同意」激活即可。用过模拟器之后 8012 会变成「已经运行过模拟器」，飞机断电重启一次就好。

**2. 图传白屏 / 黑帧 / 绿色花屏。** Mavic Mini 的图传没有 IDR 关键帧（滚动帧内刷新），格式还会在 HEVC 和 H.264 之间变化。手机硬件解码器（包括 `DJICodecManager`）要等关键帧，所以一直不出图。`Live.kt` 的做法：
- 用 Google 软件解码器 `c2.android.avc.decoder` / `c2.android.hevc.decoder` 常驻解码。
- 按 AUD 把同一帧的多个 slice 拼成完整的 access unit 再送解码器。
- 要看画面时只把最新一帧压成 JPEG 上传。
- `VideoFeeder` 的监听要在飞机连上之后再注册，否则收不到数据。

## 当前进度

- [x] 云端 ⇄ 手机 ⇄ 遥控器 ⇄ 飞机全链路
- [x] 远程诊断、激活
- [x] 室内起飞，1.2 m 视觉定位悬停，远程降落
- [x] 图传软件解码看实时画面
- [ ] 点动 / 环绕的方向映射真机验证（代码里有“6 秒不接近目标自动中止”的保护）
- [ ] 插 SD 卡拍原图 → COLMAP / 3D Gaussian Splatting 重建
- [ ] App 端飞行轨迹显示

欢迎提 Issue 和 PR，尤其是：其它大疆机型的适配、3D 重建流水线、更好的 Agent 交互。

## 构建与部署

**App**：JDK 17 + Gradle 8.7 + AGP 8.5.2，`minSdk 24`。
1. 复制 `android/drone.properties.example` 为 `drone.properties`，填入你在 [DJI 开发者中心](https://developer.dji.com/) 申请的 App Key（包名 `top.mambabot.drone`），以及中转地址和 token。
2. `gradle assembleDebug`，把 APK 装到手机上。
3. 手机用 OTG 线接遥控器，弹窗选 MambaDrone。

**中转**：
```bash
cd relay && python3 -m venv .venv && .venv/bin/pip install aiohttp
head -c 24 /dev/urandom | base64 > .token
.venv/bin/python relay.py        # 公网 8792（经 nginx 反代 /drone/），本地 8793
```
nginx 里给 `/drone/` 加 WebSocket 反代（`Upgrade` / `Connection` 头），上传体积上限按照片大小设置。

## 安全

- 飞控只保留高度 ≤ 50 m（微型无人机适飞空域上限）和 App 上的红色停止键，电量、返航、禁飞区交给大疆固件。
- 首次飞行前请先在模拟器里卸桨跑 `drone simtest`，确认方向正确。
- 室内飞行注意头顶：Mavic Mini 只有下视传感器，相机看不到上方。
- 在中国大陆飞行需要在 UOM（民航局无人驾驶航空器综合管理平台）实名登记。

## License

GPL-3.0
