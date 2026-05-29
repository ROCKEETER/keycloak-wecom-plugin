# Keycloak 26 WeCom (企业微信) Identity Provider

🚀 **为全新架构的 Keycloak 26 深度定制的企业微信 (WeCom) 扫码登录插件！**

完美解决由于 Keycloak 升级 Quarkus 底层架构、Jakarta EE 迁移以及底层静态路由机制导致的各种“水土不服”与 `40029 invalid code` 等无头惨案。

## 🌟 特性
- **全系支持 Keycloak 26+** (基于 Quarkus 引擎构建)
- 适配全新 React UI 的 Provider 配置页 (完美找回神秘消失的 Agent ID 配置项)
- 适配企业微信最新 `/cgi-bin/auth/getuserinfo` 接口
- **独家黑科技防拦截机制**：无视 Quarkus 静态路由坑，精准拦截原始 `code`，彻底告别由于底层强行用错误 JSON 替换 code 而引发的 `40029` 报错！

---

## 🙏 致谢 (Acknowledgements)

本插件能够诞生，离不开开源社区前辈们的铺路，特别感谢以下开发者及项目：
* **[jeff-tian](https://github.com/Jeff-Tian)**: 提供了许多前期灵感，他的 22.x 插件曾是社区主流。
* **[kkzxak47](https://github.com/kkzxak47)**: 提供了 5 年前最原始的 `javax` 源码和宝贵的改写思路。
* **[jyqq163](https://gitee.com/jyqq163/keycloak-services-social-weixin)**: 提供了早期微信社会化登录的设计基础。

---

## 🛠️ 构建与部署 (Build & Deploy)

### 1. 环境要求
- JDK 17 或以上
- Maven 3.8+
- Keycloak 26.x+

### 2. 本地编译
克隆本仓库后，在根目录执行以下命令：
```bash
mvn clean package
```
编译成功后，在 `target/` 目录下会生成 `keycloak-services-social-wechat-work-26.0.1.jar`。

### 3. 部署到 Keycloak
将生成的 jar 包放入 Keycloak 所在的 `providers/` 目录。
```bash
cp target/keycloak-services-social-wechat-work-26.0.1.jar /path/to/keycloak/providers/
```

重新构建并重启 Keycloak 容器（请确保使用 `--optimized` 构建）：
```bash
kc.sh build
kc.sh start
```

---

## ⚙️ 配置指南

1. 进入 Keycloak Admin Console (管理控制台)。
2. 左侧菜单选择 **Identity Providers**。
3. 添加 Provider 选择 **Wechat Work (企业微信)**。
4. 填入以下配置项：
   - **Client ID**: 你的企业微信 CorpID
   - **Client Secret**: 你在企业微信后台创建的应用的 Secret
   - **Agent ID**: 你的应用 AgentId (必填！)
5. 将页面生成的 **Redirect URI** 复制到企业微信后台的“企业微信授权登录”回调配置中（记得还要配置企业微信的授信域名）。

---

## 🩸 踩坑全纪录 (我们的血泪史)

如果你好奇我们为了让这个插件在 Keycloak 26 上跑起来到底熬了多少个大夜，踩了多少个神坑，请看以下全纪录（或许能帮你排查类似问题）：

### 坑点一：Jakarta EE 迁移与消失的配置项
Keycloak 从 19 版本开始拥抱 Quarkus 架构，Java EE 规范从 `javax.*` 迁移到了 `jakarta.*`。这导致了旧版插件全部瘫痪。我们不仅进行了全局包名替换，还遇到了更深的坑：在全新的 React UI 下，如果插件的 Factory 类没有通过 `getConfigProperties()` 显式声明额外属性，UI 根本不渲染输入框！
由于一开始没声明，我们的“Agent ID”输入框神秘消失了，导致向企微发送了空的 Agent ID，疯狂报错 `40029`。

### 坑点二：企业微信 API 接口的迭代
旧版获取用户信息的接口 `https://qyapi.weixin.qq.com/cgi-bin/user/getuserinfo` 已被废弃。我们将其替换为了新的 `https://qyapi.weixin.qq.com/cgi-bin/auth/getuserinfo`，并修复了返回值大小写（由大写 `UserId` 变为了小写 `userid`）。

### 坑点三：Quarkus 静态路由黑洞与 40029 嵌套惨案 (极度隐蔽)
配置好一切后，依然报错 `40029 invalid code`！通过扒开深层日志，我们发现：
由于 Quarkus 在编译期优化了 JAX-RS 路由，我们动态加载的插件没有被注入回调路由表。Quarkus 自作主张地把企业微信扫码回调路由给了标准的 `AbstractOAuth2IdentityProvider.Endpoint`。
标准的 Endpoint 会尝试发起标准的 OAuth2 POST Token 请求，而企业微信根本不支持！企微返回了错误 JSON `{"errcode":47001,"errmsg":"data format error"}`。
**最离谱的是**：Keycloak 基类把这个报错的 JSON 当成了真正的 code，塞给了我们的 `getFederatedIdentity` 方法！我们拿着这个 JSON 去请求企微用户信息，自然就炸了！
**破局之法**：我们在代码中直接忽略了这个错误的参数，从原始的 URI Query Parameter 里“虎口夺食”，强行提取出真实的 `code`，成功绕过路由黑洞！

### 坑点四：微小版本变更引发的 NoSuchMethodError
就在即将成功之际，系统在最后一刻抛出了 `500 Internal Server Error`，报错信息为：
`java.lang.NoSuchMethodError: 'void org.keycloak.broker.provider.BrokeredIdentityContext.setIdp(org.keycloak.broker.provider.IdentityProvider)'`
原因是我们编译基于 26.0.1（方法参数为 IdentityProvider），但运行时的更高版本（如 26.6）将其改为了 `UserAuthenticationIdentityProvider`。
**解决**：我们发现 Keycloak 基类后续会自动调用绑定逻辑，于是果断删除了冗余的 `setIdp` 代码，完美度过危机！

---

## 📝 许可证 (License)

本项目采用 [MIT 许可证](LICENSE) 开源。欢迎提 Issue 和 PR，让中国开发者的生态越来越好！

*— Created with ❤️ by ak & Antigravity IDE*
