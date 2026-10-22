# AGENTS.md

本文件是本仓库面向所有 AI 编程代理（Claude Code、MiMo、Cursor、Copilot 等）的统一规范，在本仓库工作前请先阅读。规范只维护在这一处，其它代理的专属文件（如 `CLAUDE.md`）只做引用，不要重复或分叉内容。

## 项目概述

Api Savior 是一个 IntelliJ IDEA 插件，功能包括：
- 从 Spring MVC Controller / Dubbo / Feign 接口生成 HTTP 接口文档（Markdown、HTML）
- 导出到 Postman、生成 cURL 命令
- 实体类快速生成 getter/setter（Alt+Enter 触发）
- Search Everywhere 中搜索 API 并跳转（`Ctrl + \` 或 `Ctrl + Alt + N`）

## 构建与运行

```bash
# 构建插件
./gradlew build

# 运行调试（启动一个新的 IDEA 实例加载此插件）
./gradlew runIde

# 打包发布版本
./gradlew buildPlugin
```

- 项目 SDK 要求 JDK 11+
- Gradle JVM 也需设置为 11
- `gradle.properties` 中 `descriptionFile` 和 `changesFile` 指向 `parts/` 下的 HTML 文件
- 阿里云 Maven 仓库已配置在 `build.gradle` 中

## 技术栈

- **IntelliJ Platform Plugin**（Gradle 构建，`org.jetbrains.intellij.platform` 插件 2.10.5）
- **FreeMarker** 模板引擎（`org.freemarker:freemarker`），用于生成文档
- **MDTool**（`com.youbenzi:MDTool`），HTML 转 Markdown
- **Lombok**
- 目标 IDE 版本：`sinceBuild = 251`（IntelliJ 2025.1+）

## 代码架构

源码位于 `src/main/java/cn/gudqs7/plugins/`，分为 6 个主要包：

### `common/` — 公共基础设施
- `base/action/` — Action 基类（右键菜单 Action、批量生成 Action、IntentionAction）
- `base/reader/` — 数据读取接口（`IStructureAndCommentReader`），从 `StructureAndCommentInfo` 转换为目标数据
- `resolver/comment/` — 注解解析器（`AnnotationHolder`），从 PsiElement 读取注解和 javadoc 注释，封装为 `CommentInfo`
- `resolver/structure/` — 结构解析器（`StructureAndCommentResolver`），递归解析参数/返回值的类型结构，生成 `StructureAndCommentInfo` 树
- `util/` — 工具类，包括 PSI 操作工具、文件工具、JSON 工具、Http 工具、FreeMarker 工具等
- `enums/` — `PluginSettingEnum`（所有可配置项定义）、`FieldType`、`HttpMethod` 等

### `savior/` — 核心：API 文档生成
- **action/** — 用户触发的 Action 入口（右键菜单项），按场景分为：
  - `restful/` — Spring MVC 接口（生成文档、cURL、参数文档）
  - `rpc/` — Dubbo/Feign 接口
  - `batch/` — 批量生成（HTML、Postman、Amp、OneApi 导出）
- **savior/base/** — `AbstractSavior<T>` 核心流程：获取方法的 PsiMethod 列表 → 解析注解/注释 → 解析参数和返回值结构 → 交给子类生成目标数据
  - `BaseSavior` 收集通用数据（IP、URL、contentType 等）
- **savior/more/** — 具体输出实现：`JavaToDocSavior`（Markdown 文档）、`JavaToPostmanSavior`、`JavaToCurlSavior`、`JavaToAmpSavior`、`JavaToOneApiSavior`
- **theme/** — 接口风格策略（策略模式）：`RestfulTheme` 和 `RpcTheme`，决定模板路径、方法过滤逻辑、JSON 示例生成方式
- **reader/** — 数据读取器：`Java2MapReader`（结构→Map 示例数据）、`Java2ApiReader`（结构→字段表格数据）、`Java2ComplexReader`、`Java2BulkReader`
- **util/** — `RestfulUtil` 处理 URL 拼接等

### `generate/` — 实体类代码生成
- `setter/` — 生成所有字段的 set 方法（支持 defaultVal、builder、chain 模式）
- `getter/` — 生成所有字段的 get 方法
- `convert/` — 生成对象转换代码（DTO ↔ VO 等）
- `postfix/` — PostfixTemplate 支持（如 `.allSetter` 后缀触发）
- 注意：IntentionAction 注册在 `plugin.xml` 中已被注释掉（新版 IDEA 兼容问题），仅 PostfixTemplate 仍生效

### `search/` — Search Everywhere 集成
- `ApiSearchContributor` / `ApiSearchContributorFactory` — 提供 Search Everywhere 的 "Api" Tab
- `GotoApiAction` — Ctrl+\ 快捷键跳转
- `ApiResolverService` — 扫描项目中的 API 并构建索引，结果缓存在内存（以 PSI 修改计数为版本）与磁盘
- `index/` — 接口索引的磁盘快照（`ApiIndexSnapshot`），按源文件分组并记录文件时间戳，冷启动时只重建文件变化的类；快照落在 IDEA 系统缓存目录的 `api-savior-index/` 下，不写入项目目录
- `settings/` — Api Savior 设置页（Settings → Other Settings），展示索引文件位置、大小与内存缓存统计，并提供「重建索引 / 清理索引缓存」入口

### `diagnostic/` — 错误上报
- `SaviorIssueSubmitter` — 接入 IDEA 错误处理组件，插件报错时可一键上报到 GitHub Issue

### `tools/` — 辅助代码生成
- `GenerateQueryApiDeclarationAction` / `GenerateUpdateApiDeclarationAction` — IntentionAction，生成查询/更新 API 声明

## 数据流

```
PsiClass/PsiMethod（IDEA PSI 元素）
  → AnnotationHolder 解析注解 + javadoc → CommentInfo
  → StructureAndCommentResolver 递归解析类型结构 → StructureAndCommentInfo 树
  → IStructureAndCommentReader 转换为目标数据（Map/JSON/字段列表）
  → Theme 策略处理（过滤、URL 拼接、示例生成）
  → FreeMarker 模板渲染 → 输出（MD/HTML/Postman/cURL）
```

## FreeMarker 模板

位于 `src/main/resources/template/ftl/`：
- `restful/method.ftl` + `restful/field.ftl` — MVC 接口文档模板
- `rpc/method.ftl` + `rpc/field.ftl` — RPC 接口文档模板
- `html/html-doc.ftl` + `html/index.ftl` — HTML 格式文档模板

模板路径前缀由 `Theme.getPathPrefix()` 返回，可通过配置文件覆盖自定义模板。

## 插件配置

用户可在项目中放置 `docer-config.properties` 文件自定义生成行为，所有配置项定义在 `PluginSettingEnum.java`。常用配置如 `default.ip`、`default.url`、`dir.root` 等。

## 插件注册

`src/main/resources/META-INF/plugin.xml` 定义了所有 Action、Extension 和快捷键。菜单项分两组：
- `EditorPopupMenu` — 编辑器右键菜单（单个文件/类操作）
- `ProjectViewPopupMenu` — 项目视图右键菜单（批量操作）
