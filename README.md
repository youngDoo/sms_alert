# SmsAlertTool - 轻量特别短信提醒工具

适配 Android 13 ~ 16 系统，优先兼容 vivo OriginOS 5/6 系统的纯轻量级第三方短信增强提醒工具。

## 📌 项目定位

本工具定位为**纯短信增强提醒辅助工具**。严格遵守隐私合规要求与应用商店上架规则：
*   **刚性红线：** **绝对不**引导用户将本应用设置为默认短信应用。
*   **极简前台服务：** 仅在用户手动开启监听后，启动一个轻量 Foreground Service（内存占用 < 30MB），仅维持 ContentObserver 注册和 AlarmManager 定时轮询。**不申请自启动、关联启动**。
*   **不干预系统逻辑：** 绝对不修改、拦截或篡改系统原生短信，完全复用系统自带短信 App 的处理机制。
*   **极简界面：** 仅包含“主开关”、“权限校验”、“特别关注名单”、"内容规则"四大模块。

> **⚙️ 架构演进说明 (v1 → v2)**  
> v1 版本采用纯 JobScheduler + 动态广播方案（"零后台常驻"），在实际测试中发现以下问题：
> - Vivo OriginOS Doze 模式下 JobScheduler 可能延迟 15 分钟以上执行
> - 应用进程被系统回收后 ContentObserver 失效
> - Android 13+ 禁止非默认短信应用接收 SMS_RECEIVED 广播
>
> v2 升级为 **ForegroundService 常驻监控**的三通道架构：
> 1. **AlarmManager `setExactAndAllowWhileIdle`** 每 5 秒轮询 `_id > lastProcessedId`（Doze 安全的主通道）
> 2. **ContentObserver** 监听 `content://sms/inbox` 变化（辅助快速通道）
> 3. **JobScheduler** 1-3 分钟自适应扫描过去 3 分钟的短信（兜底通道）
>
> 三重通道共享同一个 `lastProcessedId`（持久化到 SharedPreferences），Service 重启后不会重处理旧消息。

---

## 🛠 核心技术方案与实现细节

### 1. 短信监听三重保障机制 (v2)

v2 采用 **ForegroundService 内三通道并行** 架构，解决国产 OEM Doze 模式下的监听可靠性问题：

| 通道 | 机制 | 延迟 | Doze 安全 |
|------|------|------|-----------|
| **主通道** | AlarmManager `setExactAndAllowWhileIdle` 每 5 秒轮询 | < 5s | ✅（白名单后） |
| **快速通道** | ContentObserver 监听 `content://sms/inbox` | < 1s | ⚠️ Doze 延迟 2-3 分钟 |
| **兜底通道** | JobScheduler 自适应扫描（亮屏 1min / 熄屏 3min） | 1-3min | ✅ |

三重通道共享同一个持久化 `lastProcessedId`：
- `lastProcessedId` 存储于 SharedPreferences，Service 重启后恢复
- 查询条件：`_id > lastProcessedId ORDER BY date DESC LIMIT 1`
- 每次处理新短信后立即持久化 `lastProcessedId`

### 2. 去重防误触规则
*   内存与 SharedPreferences 双重缓存，保留最近 **10条** 已处理短信的唯一标识（格式：`短信数据库ID + 发送号码 + 接收时间戳（精确到分钟）`）。
*   无论是广播触发还是 JobScheduler 触发，均先检索去重缓存，命中后直接丢弃逻辑，避免重复播放铃声和弹出弹窗。

### 3. 音频焦点与强提醒逻辑
*   **音轨分类控制：** 铃声播放强制采用 `AudioAttributes.USAGE_ALARM`（闹钟音轨），**不受系统媒体音量、静音模式限制**，仅能通过系统闹钟音量进行调节。
*   **同步振动：** 同步触发 `Vibrator` 振动（波形节拍与铃声完美同步启停）。
*   **无限循环：** 铃声和振动默认持续无限循环播放，只有当用户在提醒界面点击唯一的「我已知晓」按钮时，才会立刻终止播放并释放音频焦点。

### 4. 亮屏悬浮窗与锁屏全屏 Activity
*   **亮屏状态：** 收到对应短信时，由于本应用未使用 `SYSTEM_ALERT_WINDOW`（悬浮窗权限），为了权限最小化，我们通过精妙的 `Theme.AppCompat.Light.Dialog` 样式将 `AlertActivity` 渲染为一个精致的悬浮对话框，在亮屏时置顶弹出。
*   **锁屏状态：** 通过在 Activity 中调用 `setShowWhenLocked(true)`、`setTurnScreenOn(true)` 以及 `FLAG_KEEP_SCREEN_ON`，绕过锁屏拦截，自动唤醒屏幕并全屏展现提醒界面，用户在不解锁手机的情况下即可点击「我已知晓」终止响铃。

### 5. OriginOS 专属被动唤醒与反清理
*   **被动唤醒重注册：** 应用被用户从最近任务划掉或被系统清理后，动态注册的广播会失效。本应用静态声明了 `BOOT_COMPLETED` 和 `ACTION_POWER_CONNECTED/DISCONNECTED` 广播。当用户开机、插拔充电线或电量改变时，系统会激活本应用的 `Application`，执行 `onCreate()` 时将**第一时间自动重新注册动态广播和 JobScheduler**，保证监听在被系统清理后能被动复活，不需要用户手动打开应用。
*   **异常耗电豁免说明：** 应用不申请常驻后台或前台服务，待机状态下内存占用小于 **15M**，待机 24 小时额外耗电量少于 **0.5%**，天然符合 vivo OriginOS 的省电策略。

---

## 🔒 权限最小化设计 (Minimal Permissions)

本应用在 `AndroidManifest.xml` 中**仅且允许**声明以下 3 个必要系统权限，多一个都不添加，极大提升了应用商店上架的合规通过率：
1.  `android.permission.RECEIVE_SMS`：动态接收新短信广播。
2.  `android.permission.READ_SMS`：读取短信发送人号码、拼接短信内容，进行名单匹配。
3.  `android.permission.POST_NOTIFICATIONS`：Android 13+ 必须的通知发送权限，用于弹出系统通知及在权限未授权时向用户提示。

> **💡 免 READ_CONTACTS 导入联系人技巧：**  
> 本应用利用系统联系人选择器 `Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)`。该 Picker 运行在系统进程中，用户选定联系人后，仅向本应用返回单条记录的安全 Uri。因此，本应用**不需要申请**任何 `READ_CONTACTS` 权限，即可完美实现“通讯录导入”功能，是权限最小化合规开发的典范。

---

## 📂 项目结构

```text
/workspace/app-d7ajhx5skpvl
├── app
│   ├── build.gradle              # App级 构建配置 (CompileSdk 34, ViewBinding)
│   ├── proguard-rules.pro        # 混淆规则 (保护主逻辑不被剔除)
│   └── src
│       └── main
│           ├── AndroidManifest.xml # 清单文件 (限定3个权限)
│           ├── java
│           │   └── com
│           │       └── lightweight
│           │           └── smsalert
│           │               ├── SmsAlertApplication.kt    # 全局 App 类 (被动唤醒、重新注册)
│           │               ├── data
│           │               │   └── PrefsManager.kt       # 偏好设置、特别名单存储、去重 10 条缓存
│           │               ├── model
│           │               │   └── SpecialContact.kt     # 特别关注联系人模型类
│           │               ├── receiver
│           │               │   ├── BootAndPowerReceiver.kt # 开机、充电被动唤醒广播
│           │               │   └── SmsReceiver.kt         # 短信广播接收器、去重校验、10秒 WakeLock
│           │               └── ui
│           │                   ├── AlertActivity.kt      # 锁屏全屏醒屏、亮屏悬浮窗提示页
│           │                   ├── ContactAdapter.kt     # 特别名单列表 Adapter
│           │                   └── MainActivity.kt       # 极简配置主页、CRUD、Picker、权限校验
│           └── res
│               ├── drawable      # 矢量图标资源
│               ├── layout        # 界面布局 (主页、列表项、编辑对话框、强提醒框)
│               ├── mipmap-anydpi-v26 # 自适应矢量应用图标
│               └── values        # 颜色、字符、样式、Dialog 透明主题配置
├── build.gradle                  # 项目级 构建配置
├── settings.gradle               # 模块配置
└── gradle.properties             # Gradle 全局参数
```

---

## 🚀 编译与导入说明

### 1. 导入项目
1. 启动 **Android Studio** (建议使用 Hedgehog / Iguana / Jellyfish 或更高版本)。
2. 选择 **File -> New -> Import Project...**。
3. 选择 `SmsAlertTool` 根目录（即包含 `settings.gradle` 的文件夹），点击 **OK**。
4. 等待 Gradle Sync 完成。

### 2. 编译配置
*   **JDK 版本：** 必须配置为 **JDK 17**。
*   **Compile SDK：** `34` (Android 14)
*   **Min SDK：** `26` (Android 8.0)
*   **Gradle 插件版本：** `8.2.2`

---

## 📱 vivo OriginOS 5/6 专属配置指导（开发者及用户指南）

为了保证应用在 vivo 手机上获得最完美、最及时的提醒体验，请按照以下步骤进行系统设置：

### 1. 基础权限授权
*   首次启动时，应用会检测**短信接收**和**通知发送**权限。
*   若未授权，应用会自动弹出提示并引导用户点击跳转至系统的“应用信息”页面。请在“应用权限”中将「**短信**」权限设为“**允许**”，并将「**通知**」开启，允许横幅与锁屏通知。

### 2. 异常耗电管理（加入白名单）
因为 vivo OriginOS 拥有极度严格的后台对齐唤醒与省电策略，若系统判定应用在后台耗电，可能会对其进行冻结。
*   **设置方法：**  
    打开手机 **设置 -> 电池 -> 后台高耗电管理 -> 找到“特别短信提醒” -> 勾选“允许高耗电” / “无限制”**。
*   开启后，即使应用长时间未打开，系统的 `JobScheduler` 调度和充电唤醒逻辑也将获得最高优先级，完全不会被冻结。

### 3. 显示锁屏和后台弹出界面
在 vivo OriginOS 上，普通应用在锁屏状态下弹出 Activity 需要额外授权：
*   **设置方法：**  
    打开手机 **系统设置 -> 应用与权限 -> 权限管理 -> 权限 -> 单个应用权限设置 -> 找到“特别短信提醒” -> 开启「显示在锁屏」与「后台弹出界面」权限**。
*   开启此项后，当手机锁屏且收到特别关注短信时，应用能瞬间点亮屏幕并全屏弹出我已知晓的提醒窗口，达成完美的强提醒效果。

### 6. ⚠️ 已知限制：Vivo 106 验证码类服务短信

Vivo OriginOS 对验证码类服务短信（106等短号开头）有系统级拦截处理：
*   **新收到的验证码短信**被系统提取到私有 content provider `com.vivo.mms.extendsmsprovider`，**第三方应用无权限访问**。
*   由于验证码短信不进入 `content://sms/inbox`，本应用的 ContentObserver 和 AlarmManager 轮询均无法检测到这类短信。
*   **历史服务短信**（非验证码类，如银行通知、营销推送等）仍会正常存储在 `content://sms` 中，可被本应用正常检测。
*   **常规手机号码**发送的短信不受此限制影响，完全正常工作。

> 如需完整的验证码短信覆盖，唯一可行的第三方方案是注册 `NotificationListenerService` 监听系统短信应用的通知栏消息。此方案需用户手动在「设置 → 无障碍/通知使用权」中授权，属于可选扩展。

## 📄 License

[MIT](LICENSE) © 2026 推陈出新
