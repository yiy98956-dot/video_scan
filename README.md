<div align="center">

![License](https://img.shields.io/badge/license-MIT-blue)
![Go](https://img.shields.io/badge/Go-1.21+-00ADD8?logo=go)
![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.5-6DB33F?logo=springboot)
![React](https://img.shields.io/badge/React-19-61DAFB?logo=react)
![TypeScript](https://img.shields.io/badge/TypeScript-5.x-3178C6?logo=typescript)
![Redis](https://img.shields.io/badge/Redis-cache-DC382D?logo=redis)
![MySQL](https://img.shields.io/badge/MySQL-database-4479A1?logo=mysql)

</div>

# 🎬 Film Horizon

> 集影视数据采集、视频流代理、用户互动与在线播放于一体的全栈视频平台。  
> 前后端分离，各模块职责清晰，易于扩展和维护。

---

## 系统架构
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│ React 前端 │────▶│ Java 后端 │────▶│ Go 服务 │
│ （用户界面） │ │（业务逻辑/API） │ │（数据采集/代理） │
└─────────────────┘ └─────────────────┘ └─────────────────┘
│ │ │
▼ ▼ ▼
用户交互 用户认证/互动 影视数据源
视频播放 缓存/数据库 视频流代理

text

| 模块 | 职责 |
|------|------|
| React 前端 | 用户交互、视频播放、状态展示 |
| Java 后端 | 用户认证、互动功能、业务编排、缓存策略 |
| Go 服务 | 多源影视数据采集、防盗链视频流代理、直播支持 |

---

## 核心功能

### Go 服务（数据采集与代理）
- 从配置的 CMS 源采集电影、电视剧、综艺、动漫等元数据
- 代理 m3u8 / ts / mp4 视频流，处理防盗链、CORS、UA 伪装
- 双层缓存：内存 200MB + 磁盘 20GB，支持 TTL 过期
- 分类管理：一二级分类树，支持分类匹配、显示/隐藏
- 搜索引擎：标题搜索、自动补全、高级筛选
- 直播支持：IPTV 直播频道代理、m3u8 重写
- 监控面板：运行状态、缓存命中率、采集进度

**主要 API 端点**
- `/api/movies/*` – 影片列表、详情、搜索、分类
- `/api/category/*` – 分类树管理
- `/api/collect/*` – 采集控制
- `/api/live/*` – 直播频道

### Java 后端（业务逻辑）
- 用户系统：注册、登录、JWT 认证、个人资料
- 视频服务：影片列表、详情、分类筛选（代理 Go 服务）
- 互动功能：点赞、收藏、关注、评论
- 播放历史：进度记忆、断点续播
- 搜索服务：关键词搜索
- 管理后台：分类可见性管理、用户管理
- 安全机制：可选的 API 响应加密
- Redis 缓存：视频详情、列表缓存

### React 前端（用户界面）
- 首页：热门推荐、分类导航
- 浏览页：影片列表、分类筛选、分页
- 详情页：影片信息、播放源选择、评论、互动
- 播放页：HLS.js 播放器、进度记忆、续播
- 搜索页：关键词搜索
- 分类页：分类树导航
- 用户系统：登录/注册、个人中心、收藏/历史
- 管理页：分类管理、用户管理

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 前端 | React 19、React Router 7、TypeScript、Tailwind CSS 4、Vite、HLS.js |
| 后端 | Spring Boot 3.2.5、MyBatis-Plus、MySQL、Redis、Spring Security、JWT、Knife4j |
| 采集/代理 | Go、内存/磁盘缓存、m3u8 重写、IPTV 代理 |
| 部署 | 各模块可独立部署，支持 Docker 容器化 |

---

## 本地运行

详细步骤请参考各子模块的 README。

**Go 服务**
```bash
cd go-service
go run main.go
Java 后端

bash
cd java-api
mvn spring-boot:run
React 前端

bash
cd react-ui
npm install && npm run dev
项目结构
text
film-horizon/
├── go-service/         # Go 数据采集与代理服务
├── java-api/           # Spring Boot 业务后端
├── react-ui/           # React 前端应用
└── docs/               # 架构文档、API 说明
贡献
欢迎提交 Issue 和 PR，一起完善平台功能。
如有任何问题，请通过 GitHub Discussion 交流。

许可证
本项目基于 MIT License 开源。
