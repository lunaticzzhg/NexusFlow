# Orbit 认证与会话

- Orbit 使用 Keycloak / OIDC Authorization Code + PKCE；KMP 客户端不保存密码或 client secret。
- access token 使用安全平台存储；刷新、登出、token 失效和 user / tenant 切换必须使旧网络请求、SSE 与缓存范围失效。
- API 身份来自服务端签发 token；客户端不以 `userId` 或 `tenantId` 请求参数伪造身份。第三方日历等 OAuth 凭证由后端 Tool Gateway 保存，客户端和 AI 都不能读取。
- 登录和浏览器回调是平台原子能力；共享层只持有结构化结果与会话状态。回调深链必须校验 state/PKCE 关联并回到原请求 owner。
