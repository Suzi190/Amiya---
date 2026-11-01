# Miya 小手机 APK（Android WebView 壳）

把 `mingruis-miya-main`（纯静态 PWA）装进一个原生 Android 壳，由 **GitHub Actions 云端编译**，
本机不需要 JDK / Android SDK。

## 一、怎么拿到 APK（推荐）

构建完成后，APK 会自动发布到 **Releases**，两种下载方式任选：

1. **手机/电脑直接下载（最省事）**
   打开 <https://github.com/Suzi190/Amiya---/releases/tag/apk-latest>
   → 点 `miya.apk` 下载 → 传到手机安装（允许「安装未知来源应用」）。

2. **从 Actions 产物下载**
   仓库 **Actions** → 最新的 **Build Miya APK** 运行 → 页面底部 **Artifacts** → `miya-apk.zip` → 解压得到 APK。

构建触发方式：改动 `android/**`、`mingruis-miya-main/**` 或 workflow 文件时自动构建；
也可以在 **Actions → Build Miya APK → Run workflow** 手动触发。

> 每次构建都会覆盖 Releases 里的 `apk-latest`（同一个包名、同一个 debug 签名），
> 手机上直接覆盖安装即可，**已有数据不会丢**。


## 二、这套壳做了什么

| 需求 | 实现 |
| --- | --- |
| 完整全屏 | 隐藏状态栏 + 导航栏、系统栏透明（不是黑条）、内容延伸到刘海/挖孔区（`shortEdges`），下拉/上滑仅临时浮出系统栏 |
| 键盘不遮输入框 | API 30+ 读取 IME insets，把键盘高度转成 WebView 底部边距（等价 `adjustResize`）；API 24~29 走系统 `adjustResize`。页面 `100dvh` / `visualViewport` 会自然缩小 |
| 数据导入 | 实现 `onShowFileChooser`，点「导入」直接打开系统文件选择器（角色卡 `.png` / `.json` 都能选） |
| 数据导出 | 拦截 `miyaDownloadBlobAsync`，分块写入 **下载/Miya/**（Android 10+ 走 MediaStore，无需存储权限），成功后 toast 提示路径 |
| 返回手势 | 系统返回（含左右边缘滑动）→ 网页先按层关闭（弹窗 → 面板 → 聊天房间 → 各 App 层 / 返回按钮）→ 再退页面历史 → 已在桌面则**退到后台不退出** |
| 本地通知 | Android 13+ 启动时请求通知权限；`miyaShowSystemNotification` 桥接到原生通知渠道（高优先级、可震动、点击回到对应聊天） |
| 深链 | 点通知 → 打开 App 并进入对应聊天（`__miyaDeepLink` → `MiyaChatNotify.openChat`） |
| 网络 | 允许 http 明文（自填 API 地址），站外链接交给系统浏览器 |
| 其它 | 禁用系统深色强制反色、字体缩放固定 100%、长按/滚动条隐藏、返回键不销毁进程 |

### 数据存放位置

- 旧系统（≤ Android 9）：`下载/Miya/`（若失败退回 App 私有目录）
- Android 10+：`下载/Miya/`（MediaStore Downloads）

### 关于「浏览器里的旧数据」

APK 内页面运行在 `https://localhost`（WebViewAssetLoader 提供的安全源，`localStorage`/`IndexedDB`/`OPFS`/Service Worker 都可用），
与你用浏览器访问时的源不同，**数据不互通**。请先在浏览器里「导出备份」，再在 APK 里「导入备份」。

## 三、目录结构

```
android/
├── settings.gradle / build.gradle / gradle.properties
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/miya/shell/
        │   ├── MainActivity.java   # 全屏、IME、返回、文件选择、深链
        │   ├── MiyaBridge.java     # window.MiyaNative JS 桥
        │   ├── MiyaNotifier.java   # 通知渠道 / 权限 / 展示
        │   └── MiyaDownloader.java # 分块写盘（Downloads/Miya）
        └── res/                    # 主题、图标、网络配置
.github/workflows/build-apk.yml     # 云端构建
mingruis-miya-main/js2/miya-native-shell.js  # 网页侧适配层（浏览器中自动跳过）
```

网页资源不入库：构建时把 `mingruis-miya-main/` 复制到 `android/app/src/main/assets/public/`。

## 四、本地构建（可选，需要 JDK 17 + Android SDK 34）

```powershell
# 1) 复制网页资源
Remove-Item android/app/src/main/assets/public -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force android/app/src/main/assets/public | Out-Null
Copy-Item mingruis-miya-main/* android/app/src/main/assets/public -Recurse

# 2) 编译（用你自己的 gradle 8.7）
cd android
gradle :app:assembleDebug
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
```

若使用 Android Studio：直接 `File → Open` 选择 `android/` 目录即可，
需先在 `android/local.properties` 写入 `sdk.dir=你的SDK路径`。

## 五、首次安装 / 升级说明

- 本仓库的 APK 使用**仓库内固定的调试签名**（`android/keystore/miya-debug.jks`，仅供自用，请勿用于上架分发），
  因此每次重新构建都能**直接覆盖安装**，数据保留。
- 如果你安装过最早那一版（当时 CI 用随机签名），需要先卸载一次再装新版；之后所有版本都能直接覆盖升级。
- 覆盖安装时若提示"版本号未变化"或失败，可把 `android/app/build.gradle` 的 `versionCode` 加 1 再构建。

## 六、常见问题

- **通知收不到**：设置里打开「系统通知」，并确认系统「应用通知」已允许；省电模式/后台限制可能延迟定时提醒。
- **导出的文件找不到**：文件管理器 → 下载 → `Miya`；Android 10+ 也可在「文件 → 下载」看到。
- **返回直接退出**：当前已在桌面层时按返回会「退到后台」（符合不退出预期）；若希望彻底退出，用系统任务列表划掉。
- **页面空白**：先确认 `mingruis-miya-main/index.html` 存在；CI 里由 *Stage web assets* 步骤复制。
- **侧滑返回时整屏一片“磨砂玻璃”**：网页原本会在 `body.miya-app-open` 时给桌面 `.phone`
  加 `filter: blur(10px)`（App 打开时当作虚化背景）。APK 里 App 层是全屏不透明的，这层虚化本来
  看不见，但返回瞬间（App 层 0.38 s 淡出）会露出来。`js2/miya-native-shell.js` 已注入
  `html.miya-native-shell body.miya-app-open .phone{transform:none;filter:none}` 关掉它，v1.0.2 起生效。
- **想改应用名/包名**：改 `android/app/build.gradle` 的 `applicationId`、`res/values/strings.xml` 的 `app_name`
  （改 `applicationId` 会被视为新应用，数据不继承）。
- **想换成正式签名**：在 CI 中注入 keystore 并配置 `signingConfigs`，把 `assembleDebug` 换成 `assembleRelease`。

## 七、已验证 & 调试

每次构建都会自动检查（失败会直接红叉）：

- Gradle 8.7 + AGP 8.5.2 + JDK 17 + compileSdk 34 编译通过，产出 `app-debug.apk`；
- APK 内确实包含 `assets/public/index.html`、`assets/public/js2/miya-native-shell.js`、`assets/public/css/style.css`
  （整站约 300 个文件，全部打进 `assets/public/`）；
- 上传 Actions 产物，并覆盖发布到 `apk-latest` Release（含固定签名）。

真机排查问题：

- APK 是 debug 包，已开启 WebView 远程调试：手机连电脑 → 电脑 Chrome 打开 `chrome://inspect`，
  可以看到页面控制台日志、DOM、网络请求（排查 API 地址、存储、通知都很方便）。
- 若页面白屏，先在 `chrome://inspect` 看 `https://localhost/index.html` 是否加载成功、
  Console 有无 JS 报错；再确认 APK 是否最新版本（Release 更新时间为准）。

