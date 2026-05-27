<div align="center">

# 🐾 Shamrock Neko QSign

**真机 QQ 原生 Sign · LSPosed 模块 · 与 unidbg QSign 接口兼容喵~**

[![Build APK](https://github.com/QsignDevelop/Shamrock/actions/workflows/build-apk.yml/badge.svg)](https://github.com/QsignDevelop/Shamrock/actions/workflows/build-apk.yml)
[![Release](https://github.com/QsignDevelop/Shamrock/actions/workflows/release.yml/badge.svg)](https://github.com/QsignDevelop/Shamrock/releases)

</div>

---

## 喵？这是什么

Shamrock 是一只住在 QQ 进程里的 **Neko 模块** 🐈

它不会自己「造」Sign，而是悄悄调用 QQ 自己的 `libfekit.so` 来签名——  
这样就不会被当成 Unidbg，也不会被当成假 Sign 啦 ✨

> **仅供学习交流喵~** 请遵守相关法律法规，24 小时内删除测试产物。

---

## 🐾 Neko 模式怎么开

1. 安装 **LSPosed (Zygisk Next 2.0.2+)** 并勾选 Shamrock 作用域 → `com.tencent.mobileqq`
2. 打开 Shamrock App → 仪表盘 → 打开 **「Neko 模式 🐾」**
3. 重启 QQ，等 MSF 进程跑起来
4. 访问 `http://<手机IP>:5700/` 看到主页就说明成功啦~

> 旧版叫「Pro 模式」，现在改名叫 Neko 模式了喵，配置会自动兼容~

---

## 📡 HTTP 接口（unidbg 兼容）

| 接口 | 说明 |
|------|------|
| `GET /` | 主页 & 统计信息 |
| `GET/POST /sign` | 获取 Sign |
| `GET/POST /sign/prewarm` | 预热 MSF Sign 服务 |
| `GET /custom_energy` | 自定义 energy |
| `GET/POST /energy` | energy（810_/812_） |
| `GET /submit` | **可选** 回调提交（有 `requestCallback` 时才需要） |
| `GET /shamrock/native_status` | 原生动态分析状态 |

### Sign 示例

```http
GET /sign?uin=123456789&cmd=wtlogin.login&seq=1&buffer=001122AABB...
     &qua=V1_AND_SQ_9.2.90_7560_YYB_D&qimei36=...&guid=...&android_id=...
```

> `qua` / `qimei36` / `guid` / `android_id` / `ver` 参数**仅为 unidbg 客户端兼容**，  
> Shamrock 真机路径里**不会使用**它们喵~ 可以传也可以不传。

### 返回格式

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "token": "AABB...",
    "extra": "CCDD...",
    "sign": "EEFF...",
    "o3did": "...",
    "requestCallback": []
  }
}
```

### Submit（可选喵~）

当 `/sign` 返回的 `requestCallback` **不为空**时，才需要把 SSO 响应提交回来：

```http
GET /submit?uin=123456789&cmd=trpc.o3...&callback_id=12345&buffer=DEADBEEF...
```

如果 `requestCallback` 是空的，**完全可以不调 `/submit`**，直接去用 Sign 就好啦~

---

## 🔬 内置动态分析

不需要外接 Unidbg 脚本喵！`libshamrocknt.so` 会在运行时：

1. Hook `RegisterNatives`
2. 捕获 `getSign` / `Dandelion.energy` 等 native 指针
3. 直接走原生 fast-path 签名

查看状态：`GET /shamrock/native_status`

---

## 🛠 构建

```bash
./gradlew :app:assembleAppDebug :xposed:assembleRelease
```

GitHub Actions 会自动构建并上传 APK 到 [Actions 页面](https://github.com/QsignDevelop/Shamrock/actions)。

---

## 📚 更多文档

- [Neko QSign 详细指南](docs/NEKO_QSIGN.md)
- [9.2.90 适配笔记](NT_9290_ADAPT_NOTES.md)

---

## 💖 贡献

欢迎 PR 和 Issue 喵~ 但请不要在公开平台宣传用于违规用途。

## 协议

[GPL-3.0](LICENSE)
