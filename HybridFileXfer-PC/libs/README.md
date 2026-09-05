# libs

vendored 第三方依赖，供 PC 端命令行手动编译与 CI（`.github/workflows/release.yml`）使用，避免每次构建联网下载。

| 文件 | 来源 | 许可证 | 用途 |
|------|------|--------|------|
| annotations-24.0.1.jar | https://repo1.maven.org/maven2/org/jetbrains/annotations/24.0.1/annotations-24.0.1.jar | Apache-2.0（与本项目 GPL-3.0 兼容） | `org.jetbrains.annotations` 可空性注解（`@NotNull` 等），仅编译期依赖，不进入运行时 |

升级时替换 jar 文件，并同步更新 `.github/workflows/release.yml` 与 `AGENTS.md` 中的版本号。
