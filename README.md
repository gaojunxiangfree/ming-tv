# 茗影院 · ming-tv

> 一开始只是想给老婆「茗雅」做一个自己用的小播放器 —— 开屏是她，问候是她，界面也随她调。
> 后来越做越顺手，功能一点点补齐，干脆开源出来：**你可以把它改成你自己的专属版本。**

一个面向 **Android 横屏大屏（平板 / 投影仪 / TV）** 的影视播放器，兼容 TVBox 生态的接口源与爬虫生态，
内置双播放内核、直播、网盘、投屏、推送配置等模块，全部用 Java + 原生 Android 实现。

**⚠️ 本项目仅供个人学习、技术研究与开发测试使用，禁止任何形式的商业用途，详见文末《免责声明》。**

---

## 目录

- [它长什么样](#它长什么样)
- [功能特性](#功能特性)
- [实测与适配情况](#实测与适配情况)
- [尚未完善 / 已知限制](#尚未完善--已知限制)
- [编译与运行](#编译与运行)
- [把它变成你自己的](#把它变成你自己的)
- [项目结构](#项目结构)
- [第三方依赖与致谢](#第三方依赖与致谢)
- [免责声明](#免责声明)

---

## 它长什么样

| 模块 | 说明 |
|---|---|
| 🏠 首页 | 分类标签 + 海报网格，投影仪/平板自适应列数，顶栏一键换源与设置 |
| 🔍 搜索 | 关键字搜索、多源快速搜索、猜你想搜与搜索记录（去重，最多 10 条） |
| 📄 详情 | 多线路切换、选集、简介、**海报位小窗预览播放**、收藏 |
| ▶️ 播放 | ExoPlayer / IJK 双内核，硬解软解切换、直链回退、二次解析、请求头透传、m3u8 去广告、字幕轨切换与外部字幕 |
| 📺 直播 | 分组与频道列表、EPG 节目单、UA 自适应、HLS/直链自动判定、失效频道自动跳台 |
| ☁️ 网盘 | WebDAV / AList 添加与浏览，播放时自动处理认证 |
| 📤 推送 | 局域网 HTTP 配置服务，**手机扫码即可推送接口地址或视频链接到电视** |
| 📡 投屏 | DLNA 接收端（DMR）：SSDP 发现 + HTTP 控制端，手机可直接投屏到本机 |
| 🕹 遥控器 | 方向键移动焦点、OK 确认、返回分层退出、菜单键唤出功能 |
| 💝 个性化 | 开屏情话（多行自定义 + 字号）、开屏背景图、**开屏语音播报**、全局文字大小三档 |

> 「茗影院」这个名字、开屏情话、语音问候，都是给茗雅的；开源版你可以在设置里全部换掉。

## 功能特性

**播放内核**
- 双内核：`ExoPlayer (Media3 1.4.1)` 与 `IJK`（经 CarGuo 预编译包），可在设置中切换，也可硬解/软解切换
- 统一内核接口 `PlayerKernel`，便于替换或扩展第三种内核
- 直链优先、`playerContent` 解析、`parse=1` 二次解析、播放请求头（Referer / Cookie / UA）全链路透传
- m3u8 广告分片过滤（`AdFilterDataSource`）
- 播放位置记忆：详情页小窗 ⇄ 全屏无缝续播

**接口与爬虫**
- 兼容 TVBox JSON 接口格式（`sites` / `lives`）与分类字段（`type_id` / `type_name` / `filters`）
- 支持 jar 爬虫、drpy JS 爬虫、网盘类站点；运行期通过 `DexClassLoader` 加载外部爬虫
- 内置本地代理服务（`127.0.0.1:9978`），打通爬虫代理类站点
- 接口整体失效时自动回退到内置测试源，站点列表按 `changeable` 规则过滤
- DoH（DNS over HTTPS）兜底，缓解部分域名解析异常

**其它**
- 收藏 / 历史（Room 本地数据库）
- 全局文字大小三档缩放（`TextScaleUtil`），大屏远距离观看更友好
- 全流程容错：单个坏源不会拖垮 App，爬虫 native 解密失败、主线程阻塞等均已做兜底

## 实测与适配情况

| 项目 | 情况 |
|---|---|
| **大屏横屏（平板 / 投影仪）** | ✅ 已完成真机验证：首页、详情、小窗预览、全屏播放、返回续播、直播、设置、网盘、投屏等链路均实测通过 |
| **手机竖屏** | ❌ **尚未适配**。界面按横屏布局设计，竖屏下会出现元素裁切/错位，仅建议横屏使用 |
| 验证机型 | Android 15 真机（型号 PA2373，2800×1968 横屏） |
| 最低系统 | Android 5.0（API 21）及以上，`targetSdk 34` |
| CPU 架构 | `arm64-v8a` / `armeabi-v7a` / `x86_64` |
| 操作方式 | 遥控器方向键 + OK 键、触屏点击均可 |
| 语音播报 | 依赖系统 TTS 引擎（部分精简版电视盒子/投影仪未内置，则静默无声，不影响其它功能） |

> 💡 **强烈建议用真机测试**：模拟器上部分加密源的原生库解密失败、IPv6 路由不通、竖屏会裁切横屏 UI，容易误判。

## 尚未完善 / 已知限制

- **手机竖屏未适配**，触屏竖屏场景基本不可用
- **弹幕**：已有弹幕视图组件，但未接入弹幕数据源（需自行对接第三方弹幕 API）
- **P2P / 磁力**：未集成第三方 P2P SDK，磁力、迅雷等地址会降级为「复制链接」，需切换普通线路
- **IJK 内核限制**：不支持 AES-128 加密的 HLS 流，这类源会自动回退到 ExoPlayer
- **网盘类站点**（玩偶、无名等）：需在接口 JSON 里自行配置站点级请求头，App 内暂无配置界面
- **直播源质量**：依赖第三方源，部分频道长期无信号属源侧问题
- **无内置在线升级**：版本更新需手动安装新 APK
- iOS / TV 系统级适配未涉及，仅 Android

## 编译与运行

**环境要求**：JDK 17、Android SDK（compileSdk 34）、Gradle Wrapper 自带

```bash
# 调试包
./gradlew :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk

# 发布包(需要签名配置, 见下)
./gradlew :app:assembleRelease
# 产物: app/build/outputs/apk/release/ming-tv-v<版本号>.apk
```

**关于签名**：仓库**不包含**任何签名文件（`.keystore` / `.jks` 已被 `.gitignore` 排除）。
`app/build.gradle` 会在根目录存在 `seanming.keystore` 时才应用签名配置，否则以未签名方式打包，你完全可以换成自己的：

```bash
# 用环境变量覆盖签名信息(推荐)
export SEANMING_STORE_PASSWORD=你的密码
export SEANMING_KEY_ALIAS=你的别名
export SEANMING_KEY_PASSWORD=你的密码
```

**一键发版**：仓库自带 `release.sh`，一条命令完成「改版本号 → 编译 → 提交 → 推送 → 建 Release → 上传 APK」：

```bash
./release.sh 1.0.3              # 发新版本
./release.sh 1.0.3 --dry-run    # 只做前置检查, 不改任何东西
./release.sh 1.0.3 --draft      # 先建草稿 Release
```

> 脚本需要 GitHub 令牌（写权限），请放在本地 `.gh_token` 文件或 `GH_TOKEN` 环境变量里 —— **该文件已在 `.gitignore` 中排除，切勿提交**。
> 脚本默认只允许在 `main` 分支发版，避免把开发分支上的半成品发出。

**分支约定**：`feature/*` → `dev`（集成自测）→ `main`（生产/发版）。

## 把它变成你自己的

App 的名称、开屏、问候语都可以在设置里改，不需要改代码：

| 想改什么 | 在哪里改 |
|---|---|
| 开屏情话 | 设置 → 开屏页 → 情话展示 / 自定义情话（支持多行、`文本@字号` 单独设字号） |
| 开屏背景图 | 设置 → 开屏页 → 背景图 URL（留空则使用默认渐变） |
| 开屏语音播报 | 设置 → 开屏页 → 开屏语音播报（开关）+ 自定义播报内容（留空则播报 `Hello XiaoMing`） |
| 全局文字大小 | 设置 → 播放样式 → 文字大小（标准 / 偏大 / 超大） |
| 接口源 | 设置 → 接口配置（输入 TVBox 接口地址），或首页顶栏「换源」切换 |
| 手机推送配置 | 设置 → 推送页，手机扫码打开网页即可粘贴接口地址或视频链接 |
| 网盘 | 首页 → 网盘 → 遥控器菜单键 → 添加网盘（WebDAV / AList） |

**改成完全属于你的 App**：改 `app/src/main/res/values/strings.xml` 里的 `app_name`（应用名）与
`app_signature`（关于页签名），再换掉 `app/src/main/res/drawable/ic_logo.xml`、`ic_banner.xml`
与 `mipmap-anydpi*/ic_launcher.xml` 的图标即可。

## 项目结构

```
app/src/main/java/com/seanming/player/
├── App.java                    应用入口(初始化/全局兜底)
├── api/                        接口与数据源: ApiConfig, CspApi, DriveApi, EpgManager, M3uParser, SearchApi
├── bean/                       数据模型: Site, Vod, VodClass, LiveChannel, Result, Parse...
├── data/                       Room 数据库: 收藏 / 历史
├── server/                     LocalProxyServer(爬虫代理 9978), WifiConfigServer(推送配置 9753)
├── service/                    DlnaService(DLNA 接收, SSDP 1900 + HTTP 9754)
├── spider/                     爬虫加载与调度: SpiderLoader, SpiderManager, ReflectSpider, DrpySpider
├── ui/
│   ├── home / detail / play / live / search / drive / collect / history / settings / splash / push / web
├── util/                       PlayerKernel, ScreenUtil, TextScaleUtil, SafeDns, OkHttpUtil, QRCodeUtil...
app/src/main/assets/            内置测试源 test_config.json、加密源所需 native 库
app/src/main/res/               布局(横屏)、图标、文案
```

## 第三方依赖与致谢

本项目站在很多优秀开源/免费项目之上，感谢原作者：

- [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) —— 主播放内核
- [GSYVideoPlayer / CarGuo 预编译 IJK](https://github.com/CarGuo/GSYVideoPlayer) —— IJK 内核
- [OkHttp](https://github.com/square/okhttp)、[Gson](https://github.com/google/gson)、[Glide](https://github.com/bumptech/glide)
- [Room](https://developer.android.com/training/data-storage/room)、[Lottie](https://github.com/airbnb/lottie-android)、[ZXing](https://github.com/zxing/zxing)
- [QuickJS](https://github.com/HarlonWang/quickjs-android) —— drpy JS 爬虫引擎
- TVBox 生态及其接口/爬虫规范

> 说明：`app/src/main/assets/` 下的部分 native 库、以及运行期下载的爬虫 jar，均来自上述第三方生态，
> 版权归各自作者所有，本项目仅为兼容部分加密源而内置/调用。如原作者不同意，请联系删除。

## 免责声明

**请在使用前仔细阅读以下内容，使用本软件即视为你已理解并接受全部条款。**

1. **用途限定**：本项目仅供**个人学习、技术研究与开发测试**使用，**严禁任何形式的商业用途**
   （包括但不限于商业运营、付费服务、搭售、以本软件引流变现等）。
2. **内容来源**：本项目**不提供、不存储、不传播任何影视内容**。软件本身只是一个播放器外壳，
   所有内容均来自使用者自行配置的第三方接口或网络资源，与作者无关。
3. **风险自担**：使用者应自觉遵守所在国家/地区的法律法规，尊重版权。**因下载、安装、使用本软件
   所产生的一切后果（包括但不限于法律风险、设备损坏、数据丢失、账号封禁），均由使用者自行承担，
   与作者无关。**
4. **接口声明**：项目中内置/示例的接口地址**仅用于开发测试**，作者不对其可用性、合法性、内容
   安全性作任何明示或暗示的保证，请勿用于生产或商业环境。
5. **侵权处理**：如本项目内容（含代码、资源、内置调试地址等）侵犯了您的合法权益，
   **请通过 GitHub Issues 联系作者，作者将在收到通知后立即删除相关内容。**
6. **无担保**：本软件按「现状」提供，不提供任何形式的担保；作者不承诺修复任何问题或提供技术支持。

---

<p align="center">
  Made with ❤️ for 茗雅 · 希望它也能被你喜欢
</p>
