# 脱敏说明

本目录是从私有生产仓库复制出来的**脱敏版**，用于公开到 GitHub。

> 原则：**原私有目录一行未改**，生产环境照常运行。本目录是独立副本。
>
> 🔴 **本文件本身不包含任何真实凭据** —— 它曾因为「把原值写进对照表」而变成最大的泄露源，
> 现已全部改为占位符表述。看到 `***` 之类就是有意脱敏，不是遗漏。

---

## 一、脱敏维度总览

| 维度 | 处理方式 |
|---|---|
| 服务器地址 / 域名 | 替换为 `example.com` / `your-server.example.com` |
| 服务器 IP、SSH 端口、root 密码 | **整份文件不复制**（见第二节） |
| 真实手机号、弱口令 | 替换为 `admin` / `admin123`（默认账号，见第五节） |
| Android 签名密钥与口令 | 不入库，改由 `keystore.properties` 本地提供 |
| 对象存储密钥、预签名链接 | 整份文件不复制 |
| 真实客户名 / 门店名 / 套账名 | 替换为 `示例连锁超市·广州店` 等 |
| 真实商品条码、进货价、订单数据 | 整份文件不复制 |
| 本机绝对路径（用户名、目录名） | 改为环境变量或相对路径 |

## 二、完全没有复制的文件（排除，不是脱敏）

这些文件**一个字节都没进本目录**：

| 类别 | 说明 |
|---|---|
| 部署 / 运维 / 发布脚本 | 内含服务器地址、SSH 端口、root 明文密码、对象存储密钥、签名口令 |
| 一次性排查脚本 | 临时排查用，含真实订单 / 价格查询逻辑 |
| 真实业务数据 | 商品条码、进货价、门店铺货量 —— 商业敏感 |
| 回归 / 测试产物 | 内部测试脚本与截图 |
| 废弃的 Capacitor 工程 | 含第二套 keystore 与口令；产物 < 10MB 即可判定为套壳，禁止发布 |
| 运行时产物 | `*.db`（含 `-wal` / `-shm`）、`tls/` 证书私钥、`*.log`、`*.png` |

## 三、代码内的替换规则

| 位置 | 原值 | 改为 |
|---|---|---|
| 全部前端 / 服务端 | 生产域名 | `erp.example.com` |
| 服务器地址 | 生产 IP | `your-server.example.com` |
| `db.js` seed() | 硬编码 `INSERT` 真实账号 | 读 `process.env.ERP_BOSS_USERNAME` / `ERP_BOSS_PASSWORD`，为空则回落默认账号（第五节） |
| `db.js` 启动日志 | 打印真实账号 | 只打印用户名，不打印密码 |
| `db.js` 平台管理员 | 硬编码真实手机号列表 | `process.env.ERP_ADMIN_ACCOUNTS`，默认为空 |
| 种子客户 / 门店 | 真实名称 | `示例连锁超市·广州店` / `示例食品批发·肇庆店` / `示例商贸·清远店` |
| 种子套账 | 真实套账名 | `示例套账` |
| `android-native/gradle.properties` | 生产服务器地址 | `https://erp.example.com`（**故意留占位符**，构建时会拦截，见第四节） |
| `android-native/build_apk.sh` | 本机 JDK / SDK 绝对路径 | 从 `JAVA_HOME` / `ANDROID_HOME` 环境变量读取 |
| `CHANGELOG.md` | 本机源码绝对路径 | 仓库内相对路径 |

## 四、🔴 构建期保险：为什么占位域名是故意留的

真实域名**不写进入库文件**，而是放在 `android-native/local.properties`（已被 `.gitignore` 排除）：

```properties
erpApiBase=https://你的服务器域名
sdk.dir=/path/to/Android/Sdk
```

入库的 `gradle.properties` 里放的是占位符 `https://erp.example.com`。构建时由
`settings.gradle` 解析并校验，**命中占位符 / 为空 / 非 https 一律直接中断构建**。

理由：占位域名打进 APK 后，要等到装到真机、连不上服务器才会发现，排查成本极高。
宁可构建失败。

> 顺带修掉了一个「保险形同虚设」的真bug —— 原先 `settings.gradle` 校验的是
> `local.properties` 的真地址，而 `build.gradle` 里 `${rootProject.ext.erpApiBase}`
> 因 `rootProject` 在该阶段没有 `ext` 属性，退化成了 `gradle.properties` 的占位符，
> 结果 **settings 校验通过、APK 却打进占位域名**。
> 现已统一挂到 `gradle.ext.erpApiBase`，并在打包点再加一次断言。
> 教训：**「校验的值」和「使用的值」必须来自同一个对象。**

三路径实测：

| 场景 | 结果 |
|---|---|
| `local.properties` 有真实地址 | `API_BASE` = 真实地址，`BUILD SUCCESSFUL` |
| 无 `local.properties`（回落占位符） | `BUILD FAILED` —— 报「仍是 Gradle 模板占位符」 |
| `-PerpAllowExampleHost=true` | 放行（仅供 CI 打演示包） |

## 五、⚠️ 源码内置的默认账号

按需求，源码保留了开箱即用的默认管理员账号：

```
用户名：admin
密码：  admin123
```

**公开部署前必须改掉。** 已加三处提示：首次建库时的启动告警、运行中检测到仍是该密码时的
再次告警（只比对哈希、不打印明文）、以及 README「默认管理员账号」小节。

> 固定默认密码 + 公开仓库 = 任何人可登录。这三处只是提示，
> 真正的修复动作只能由部署者自己完成。

## 六、验证结果

| 项目 | 结果 |
|---|---|
| 敏感串扫描 | **0 命中**（扫描时用 `git check-ignore` 排除已忽略文件） |
| JS 语法检查 | 全部通过 `node --check` |
| `db.js` 端到端建库 | 通过 —— 默认账号可登录、错误密码正确拒绝、种子数据为示例内容 |
| 构建三路径 | 真地址成功 / 占位符被拦截 / CI 豁免放行 |
| APK 终检 | `aapt2 dump badging` 版本号正确；解压 3 个 dex 扫描仅含真实服务器地址，**不含 `example.com`** |
| `.gitignore` | 无 `.db` / `.jks` / `keystore.properties` / `local.properties` / `.env` / 部署脚本误入 |
| 原私有目录完整性 | 未改动，生产环境不受影响 |

---

## 七、⚠️ 仍需你本人处理的事

**脱敏不等于轮换凭据。** 以下信息已在本地磁盘和 git 历史中存在过，
技术手段能把代码洗干净，但洗不掉已经扩散的凭据 —— 这一步只能由仓库所有者亲自完成：

1. **改服务器 root 密码**，改用 SSH key 登录，关闭 root 密码认证
   （历史上多台服务器共用同一密码，且其中一台已被爆破尝试）
2. **撤销并重新生成对象存储的 Access Key**
3. **改掉线上 ERP 管理员账号的密码**（旧口令已随历史代码视为公开）
4. **重新生成 Android 签名体系**（旧签名口令已视为公开；新密钥备份到离线介质）
5. **清理线上遗留的测试账号**
6. 若曾经把这份代码推到过**其它公开托管平台**，需一并清理历史记录
## 八、🔴 `.gitignore` 的 `*.png` 曾吞掉 25 个 Android 图标（2026-10-19）

这是开源发布当天 GitHub Actions 首次构建就**失败**的原因，值得完整记录：

**现象**

```
ERROR: app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml:3:
  AAPT: error: resource drawable/ic_launcher_background not found
```

**根因**：`.gitignore` 为了排除截图写了 `*.png`，但**忘了给 Android 资源目录开口子**。
结果 25 个应用图标（`drawable-hdpi` ~ `drawable-xxxhdpi` 的三个图+ `mipmap-*` 两个图）
只存在于本机磁盘，**从未进过 git 仓库**。

**为什么本机测不出来**

| 环境 | 结果 | 原因 |
|---|---|---|
| 本机 `assembleRelease` |✅ 成功 | 文件在磁盘上，Gradle 不管它有没有进仓库 |
| CI `actions/checkout` | ❌ 失败 | 检出的是仓库内容，磁盘上没有那些 png |

**这条的通用教训**：**「本地能构建」不等于「仓库能构建」**。
凡是 `.gitignore` 里有大范围通配（`*.png` / `*.log` / `*.tmp`）的项目，
都必须额外验证「从干净克隆构建一次」。

**修复与防复发**

1. `.gitignore` 放行：`!android-native/app/src/main/res/**/*.png`
2. 新增 `android-native/scripts/check-res.mjs`，同时校验三件事：
   - XML 里的 `@drawable/` `@mipmap/` 引用在 `res/` 下真实存在
   - `res/` 下的资源**没有**被 `.gitignore` 排除
   - `res/` 下的资源**都在 git 索引里**（这条最容易被忽略，见下）
3. 挂到 Gradle `preBuild` 与 CI 的构建前步骤，任何入口都会先跑

**🔴 第三条检查为什么必须有**

一开始只写了「查是否被 gitignore 排除」，测试时**误判通过**：
文件一旦被 `git add` 过，git 就不再把它视为「被忽略的未跟踪文件」，
`git ls-files --others --ignored` 自然列不出来。必须再查一遍
`git ls-files` 索引，才能发现「本地有、仓库没有」。

## 九、🔴 同一条通配符吃源码，**当天发作了两次**

第二个 bug 是第一个的翻版，值得单列 —— 它证明这不是偶发手滑。

**现象**（GitHub Actions 第二次构建）

```
e: .../ui/main/OrdersViewModel.kt:22:19 Unresolved reference: isNetworkError
e: .../ui/main/SaleViewModel.kt:  Unresolved reference: isNetworkError
```

**根因**：`.gitignore` 里的 `data/`（本意排除运行时数据库目录）
把 Kotlin 源码包 `com/heping/erpnative/data/` 的**全部 4 个 `.kt`** 吞掉了 ——
`Api.kt` / `CacheStore.kt` / `Crypto.kt` / `Models.kt`。
`isNetworkError` 定义在 `Api.kt` 里，所以所有引用它的 ViewModel 全都编不过。

**两次 bug 的对照**

| | 第一次 | 第二次 |
|---|---|---|
| 规则 | `*.png` | `data/` |
| 被吞的东西 | 25 个应用图标 | 4 个 Kotlin 源文件 |
| CI 症状 | AAPT `resource ... not found` | Kotlin `Unresolved reference` |
| 本机能否构建 | ✅ 能（文件在磁盘） | ✅ 能|
| 共同根因 | **`.gitignore` 通配符误伤源码** | 同左 |

**两条必须记住的规则**

1. **`.gitignore` 的目录规则要锚定根目录**。
   裸写 `data/` 会匹配**任意层级**的 `data` 目录；
   必须写 `/data/`（前导斜杠 = 只匹配仓库根那一层）。

   ```gitignore
   /data/          #✅ 只忽略根目录的 data/
   data/           #❌ 连 android-native/.../erpnative/data/ 一起忽略
   ```

2. **`.gitignore` 不支持行尾注释**（和 `.gitattributes` 是同一个坑）。
   写 `/uploads/    # 意见反馈附件` 时，git 会把 `# 意见反馈附件`
   一起当成路径去匹配，**整条规则静默失效**。注释必须独占一行。

**为什么本机一直测不出来**

`assembleRelease` 只看磁盘，不看 git 仓库。文件在磁盘上就编译通过。
所以本机自检脚本一开始只查 `git ls-files --others --ignored`，
在文件已被 `git add` 过之后**会误判通过**。

最终 `android-native/scripts/check-res.mjs` 做了六项双向验证的检查：

1. XML 里的 `@drawable/` `@mipmap/` 引用在 `res/` 下真实存在
2. 自适应图标的背景/前景/单色三层齐全
3. `res/` 下的资源没被 `.gitignore` 排除
4. `res/` 下的资源都在 `git ls-files` 索引里
5. `.gitignore` 没有行尾注释
6. **全仓库源码双向对账**：磁盘上有、仓库里没有（→ 别人克隆后缺文件），
   以及仓库里有、磁盘上没有（→ 提交记录与工作区不一致）

**通用教训**：交付一个开源仓库之前，除了「本地能跑」，还必须做一次
**「从干净克隆构建」**（`git clone` 到新目录再构建）。
`.gitignore` 的所有通配规则，只有这样才能验出来。
