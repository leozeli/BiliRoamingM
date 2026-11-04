# 哔哩漫游M

> [!IMPORTANT]
>
> 基于 [BiliRoamingX](https://github.com/BiliRoamingX/BiliRoamingX)，去除番剧解锁、大会员画质试用等功能
>
> 此 Fork 针对 Play 版本测试，仅进行维护性更新。
>
> 已在 **`com.bilibili.app.in` 6.7.1（versionCode 9161100）** 上验证默认补丁可以打上，且 `MainActivityV2.onBackPressed` 的注入在 19 寄存器方法上能通过校验。粉版 / HD 未在本版本复测。

> [!NOTE]
>
> **不提供** 预构建包
>
> **请勿在公开场合分享**，随时跑路

> [!IMPORTANT]
>
> ```plain
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
> FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
> IN THE SOFTWARE.
> ```

基于 ReVanced 实现的B站 Android 客户端增强模块。模块设置完美融入 APP 设置，功能丰富，自定义程度高。
得益于实现方式，对 APP 性能几乎没有影响，流畅、迅速、启动快。支持粉版、Play 版及 HD 版。

## 📖 主要功能

- 自由移除页面组件
- 自定义直播、视频默认清晰度
- 自定义播放速度
- 字幕样式调整，翻译、保存及导入
- 调整 APP 显示大小
- 自由复制评论及视频信息
- 双指缩放视频填充屏幕
- 调用外部下载器保存视频
- 加回频道功能
- 自动领取B币券
- 分享链接净化
- 推荐、热门、动态过滤
- 开屏页背景色跟随深色模式

## 💻 源码构建

```shell
git clone --recurse-submodules https://github.com/leozeli/BiliRoamingM.git
cd BiliRoamingM
./gradlew dist
```

- Windows 系统上使用 `gradlew.bat` 命令而不是 `./gradlew`
- 构建产物在 `build` 目录下

## 已验证版本

| 包名 | versionName | versionCode | 来源 |
| --- | --- | --- | --- |
| `com.bilibili.app.in` | 6.7.1 | 9161100 | Google Play（2026-10-21，arm64） |

```shell
./gradlew dist
java -jar revanced-cli.jar patch \
  --merge build/BiliRoamingM-integrations-1.23.6.apk \
  --patch-bundle build/BiliRoamingM-patches-1.23.6.jar \
  --signing-levels 1,2,3 \
  bilibili.apk
```

Play 6.7.1 的 `MainActivityV2.onBackPressed` 有 19 个寄存器，`p0` 实际是 `v18`。这条以及 `onCreate` / `onStart` / `onSaveInstanceState` 使用 `invoke-static/range`，避免 `VerifyError`。

`Unlock ProtoBuf` 会把 `GeneratedMessageLite` 的 private setter 改成 public。6.7.1 上 `Metrics.r` 仍用 `invoke-direct` 调 `setExtraFlag`，启动约 5 秒后 `IgHttpDns.report` 抛 `IncompatibleClassChangeError`。现在同一类里对这些方法的 `invoke-direct` / `invoke-direct/range` 会改成 `invoke-virtual`。

`Json` 里的 `SplashData`、`SplashShowData`、`DmAdvert` 在 6.7.1 已不存在。直接 `instanceof` 会在每次解析时抛 `ClassNotFoundException`。这三类改为先 `Class.forName`，找不到就跳过。

默认会打上集成注入、设置入口，以及仍然能对上指纹的 QoL 补丁（例如去广告块、分享净化、字幕样式、播放器核心、屏蔽直播间手势、自定义启动页等）。

Play 6.7.1 上指纹已失效、因此 **默认关闭**（需要时用 CLI `--include` 单独打开，失败则跳过）的补丁：

- 网络 / 配置：OkHttp、Moss、Config、Config integration、BLog、BL route、Upgrade、Cache redirect
- 播放：默认倍速、长按倍速、锁定倍速、覆盖倍速、硬件解码、画质、播放器 Toast、手势、字幕导入保存
- 界面：复制增强、深色切换、抽屉、自定义主题色、Toast、数字格式、评论时间跳转、自动点赞、音乐通知、Pegasus、预下载模块

设置页能进去，但依赖上述补丁的选项在 6.7.1 上不会生效。未重新加入番剧解锁或大会员画质试用。

Play 安装包是 bundle。打包前先把 base 与 `config.arm64_v8a`（以及需要的 dpi split）合并成单个 apk，否则 `libc++_shared.so` 不在 base 里，`libbiliroamingx.so` 不会被注入。

融合包里其它 feature module 的 `android:theme` 会被 apktool 解成十进制资源 id，打包前会改成框架透明主题，避免 aapt2 失败。

## ⬇️ 下载使用

- 参照 [revanced-cli](https://github.com/ReVanced/revanced-cli/tree/main/docs) 文档打包
  1. 下载定制版 [revanced-cli.jar](https://github.com/zjns/revanced-cli/releases/latest)
  2. 从 [releases](https://github.com/sakarie9/BiliRoamingM/releases/latest) 下载 `integrations.apk` 和 `patches.jar`
  3. 执行终端命令 `java -jar revanced-cli.jar patch --merge integrations.apk --patch-bundle patches.jar --signing-levels 1,2,3 bilibili.apk`

> [!NOTE]
>
> 若获取到的 Bilibili 为 apkm, xapk 或 apks 等格式，需要使用 [APKEditor](https://github.com/REAndroid/APKEditor) 等工具将其合并为 apk 再执行上述打包命令。

## 📃 Licence

[![GitHub](https://img.shields.io/github/license/sakarie9/BiliRoamingM?style=for-the-badge)](https://github.com/sakarie9/BiliRoamingM/blob/main/LICENSE)
