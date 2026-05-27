# 🐾 Neko QSign 使用指南

> 喵呜~ 这是一份给 Neko 们的 QSign 小手册  
> 如果你是从 unidbg QSign 迁移过来的，看这一篇就够啦！

---

## 1. 和 unidbg 有什么不同？

| | unidbg QSign | Shamrock Neko QSign |
|---|---|---|
| 运行环境 | PC / 服务器模拟 | 真机 QQ + LSPosed |
| Sign 来源 | 模拟 libfekit | **真实 libfekit** |
| 被检测为 Unidbg | 有可能 😿 | 不会（走真机原生） |
| 需要 txlib | 要 | 不要 |
| 接口格式 | `{code,msg,data}` | **相同** ✨ |

---

## 2. 快速开始

### 步骤一：环境

- Android 8+（推荐 Android 12+）
- Root + **Zygisk Next** + **LSPosed 2.0.2+**
- QQ **9.2.90**（其他版本可能需适配）

### 步骤二：开启 Neko 模式

```
Shamrock App → 仪表盘 → Neko 模式 🐾 → 开！
```

然后重启 QQ，确保已登录。

### 步骤三：测试

```bash
# 主页（看统计 & 版本）
curl http://127.0.0.1:5700/

# Sign
curl "http://127.0.0.1:5700/sign?uin=你的QQ号&cmd=wtlogin.login&seq=1&buffer=001122..."
```

---

## 3. Sign 参数说明

### 必填

| 参数 | 说明 |
|------|------|
| `uin` | QQ 号 |
| `cmd` | SSO 命令 |
| `seq` | 序列号 |
| `buffer` | hex 编码的请求体 |

### 兼容参数（可传可不传，Shamrock 不使用）

| 参数 | 说明 |
|------|------|
| `ver` | 协议版本号 |
| `qua` | QU A 字符串 |
| `qimei36` | 设备 qimei36 |
| `guid` | GUID |
| `android_id` | Android ID |

> 这些是为了让 unidbg 客户端**不用改代码**就能连 Shamrock 喵~  
> Shamrock 直接从 QQ 进程读真实设备信息，所以传了也会被忽略。

---

## 4. Energy 接口

```bash
# 自定义 salt
curl "http://127.0.0.1:5700/custom_energy?uin=123456&data=810_2&salt=001122..."

# 自动构造 salt
curl "http://127.0.0.1:5700/energy?uin=123456&data=810_2&guid=...&version=6.0.0...."
```

---

## 5. Submit 回调（可选！）

Sign 返回里有个 `requestCallback` 数组：

- **为空 `[]`** → 恭喜，不用管 submit，直接用 sign 结果就好 🎉
- **不为空** → 需要按 unidbg 流程，把每个回调的 SSO 响应提交回来：

```bash
curl "http://127.0.0.1:5700/submit?uin=123456&cmd=回调里的cmd&callback_id=12345&buffer=响应hex"
```

Shamrock 会通过 `ChannelManager.onNativeReceive` 把数据喂回 QQ 原生层。

---

## 6. 常见问题

### Q: 返回 MSF not started？

A: QQ 还没完全启动 MSF 服务喵~ 等登录完成后再试，或者调 `/sign/prewarm`。

### Q: Neko 模式开了但接口 404？

A: 确认 HTTP 服务端口（默认 5700），以及 Shamrock 是否成功注入 QQ 进程。

### Q: native_status 里 getSign 是 null？

A: libfekit 还没加载完，先调一次 sign 或等 QQ 初始化完成，RegisterNatives 会自动捕获。

---

## 7. 架构小图

```
你的客户端
    │  HTTP /sign /energy
    ▼
Shamrock HTTP Server (QQ 主进程)
    │  Binder IPC
    ▼
QSignerImpl (MSF 进程)
    │  libshamrocknt.so 原生 fast-path
    ▼
QQ libfekit.so getSign()  ← 真·原生签名 ✨
```

---

喵~ 有问题欢迎到 [GitHub Issues](https://github.com/QsignDevelop/Shamrock/issues) 留言！
