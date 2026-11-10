# 手机端 Aerial 化改造记录（2026-11-10）

把 `android-app`（星投屏手机端）从 Material 浅色风改成与 Aerial Lite 桌面同一套视觉语言。
代码改动集中在三处：`ui/theme/AerialTheme.kt`（新增）、`ui/BingBackdrop.kt`（新增）、
`MainActivity.kt`（排版与组件替换）、`res/values/themes.xml`（窗口底色）。

## 设计令牌

| 用途 | 值 |
|---|---|
| 画布 | `#000000` + Bing 每日一图（顶部 55% → 底部 90% 渐变压暗） |
| 卡片/玻璃 | `#1C1C1E`，1dp `#26FFFFFF` hairline，22dp 圆角 |
| 主文字 / 次文字 / 三级 | `#FFFFFF` / `#99EBEBF5` / `#4DEBEBF5` |
| 焦点白板 | `#F5F5F7`，内容 `#1D1D1F`（登录、导航选中、主按钮） |
| 语义色 | 在线 `#30D158`、警告 `#FF9F0A`、错误 `#FF453A` |
| 破坏性操作 | 半透明红底 `0x33FF453A` + 红字（不是整块高饱和红） |
| 字阶 | 34 / 26 / 21 / 19 / 15 / 13 / 11sp，标题负字距 |

Material 角色映射：`primary` = 焦点白，`secondaryContainer` = 焦点白（所以**填充按钮与选中态都是白板**），
`surface` = 玻璃，`background` = 黑，`onSurfaceVariant` = 次级文字。

## 关键改动

1. **主题替换**：删除 `lightColorScheme`，全部走 `AerialTheme`；`themes.xml` 改深色
   （窗口黑底、状态栏/导航栏黑、关闭浅色图标）。
2. **Bing 每日一图**：`BingBackdrop` 拉 `HPImageArchive`，缓存 6 小时到 `filesDir`，
   失败回退旧图；登录页与登录后的所有页面共用。
3. **悬浮玻璃导航栏**：替换 Material `NavigationBar`，26dp 圆角、左右内缩 20dp、
   半透明 `0xCC141416`、选中项为白色胶囊。
4. **卡片与列表**：设备卡 22dp 圆角 + hairline（选中改实底，避免壁纸透出）；
   局域网发现改成图标块 + 名称/IP + 状态胶囊 + 分隔线。
5. **去重复标题**：设备页、应用页原来「顶栏标题 + 页内标题」重复，页内标题删除。
6. **快捷入口**：`FilledTonalButton`（会跟随 `secondaryContainer` 变白）改成深色 `Surface` 板。
7. **分区标题**：`labelLarge`+SemiBold（偏大偏粗）→ `labelMedium` + 次级色。
8. **Snackbar**：Material 默认 `inverseSurface` 在黑色 App 上是刺眼白条 → 深色玻璃胶囊。

## 踩过的坑

- **Typography 里写死 `color`**：会覆盖 Material 的 `contentColor`，导致白底白字。
- **`LocalContentColor` 默认黑**：去掉写死颜色后，标题在黑色背景上直接消失；必须在主题层用
  `Surface(color=background, contentColor=onBackground)` 设定。
- **半透明容器 + 壁纸**：`primaryContainer`（18% 白）在图片上等于开窗，卡片必须用实底色。
- **同版本覆盖安装失败**：`versionCode` 已升到 28 / `0.13.8`，并且 debug 构建默认后端改为
  本机 `http://192.168.31.111:18000`。

## 已验证（真机 vivo V2302A，Android 16）

登录页（Bing 背景）、设备列表、局域网发现、遥控、投屏、应用、我的、悬浮导航栏、
破坏性按钮、分区标题——均截图确认（`tmp/phone-*.png`）。

## 弹窗与空状态（同日补）

- 13 个 `AlertDialog` 全部加上 `containerColor = surface`（玻璃色）与 `shape = 22dp`，
  破坏性确认按钮沿用红字样式；已在「解除设备绑定」弹窗上截图确认。
- 应用页空状态文案改为 `fillMaxWidth + TextAlign.Center`、上下留白 40dp，居中呈现。

## 尚未做

- 五语言文案：手机端沿用原有字符串资源，未新增语言；
- 部分二级页面（会员中心、Agent 详情）未逐屏检查。
