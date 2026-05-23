# API 完整文档

> 项目：PlayerServer — 视频点播+直播平台
> 最后更新：2026-05-12

---

## 目录

1. [Go-PlayerServer (端口 54567) — 数据服务层](#1-go-playerserver-端口-54567--数据服务层)
   - [1.1 状态与监控](#11-状态与监控)
   - [1.2 视频数据](#12-视频数据)
   - [1.3 MovieInfo 数据结构（前端最重要）](#13-movieinfo-数据结构前端最重要)
   - [1.4 视频代理](#14-视频代理)
   - [1.5 新分类系统（一二级分类树）](#15-新分类系统一二级分类树)
   - [1.6 采集管理](#16-采集管理)
   - [1.7 直播](#17-直播)
2. [Java Spring Boot (端口 8080) — 用户业务层](#2-java-spring-boot-端口-8080--用户业务层)
   - [2.1 认证](#21-认证-apiauth)
   - [2.2 用户](#22-用户-apiuser)
   - [2.3 视频（核心接口）](#23-视频-apivideos)
   - [2.4 Go 代理（透传到Go）](#24-c-代理-apicpp)
   - [2.5 分类](#25-分类-apicategory)
   - [2.6 评论/互动/历史/搜索](#26-评论互动历史搜索)
3. [前端页面规划建议](#3-前端页面规划建议)

---

## 1. Go-PlayerServer (端口 54567) — 数据服务层

提供视频数据、分类、采集、直播、状态监控。

> ⚠️ **本层接口仅供 Java 后端内部调用，前端不得直接访问。**
> 前端应使用 Java 层（端口 8080）的 `/api/videos/*`、`/api/cpp/*` 等接口。

### 1.1 状态与监控

| 路径 | 方法 | 参数 | 返回结构 | 说明 |
|:-----|:----:|:-----|:---------|:------|
| `/api/status` | GET | 无 | ServerSnapshot | 服务器状态快照 |
| `/api/health` | GET | 无 | `{"status":"running"}` | 健康检查 |
| `/api/monitor/endpoints` | GET | 无 | `map[string]uint64` | 每个端点的调用次数统计 |
| `/api/cache` | GET | 无 | CacheStats | 缓存统计 |

**ServerSnapshot 结构：**

| 字段 | 类型 | 说明 |
|:-----|:-----|:------|
| `uptime_seconds` | uint64 | 已运行秒数 |
| `total_requests` | uint64 | 总请求数 |
| `total_movies` | int | 影片总数 |
| `total_bytes_in` | uint64 | 总入站字节 |
| `cache_hit_rate` | float64 | 缓存命中率(%) |
| `cache_hits` | uint64 | 命中次数 |
| `cache_misses` | uint64 | 未命中次数 |
| `memory_used_gb` | float64 | 内存已用(GB) |
| `memory_limit_gb` | int | 内存限制(GB) |
| `disk_used_gb` | float64 | 磁盘已用(GB) |
| `disk_limit_gb` | int | 磁盘限制(GB) |
| `version` | string | 版本号 |

**CacheStats 结构：**

| 字段 | 类型 | 说明 |
|:-----|:-----|:------|
| `memory_items` | int64 | 内存缓存条目数 |
| `memory_bytes` | int64 | 内存占用字节 |
| `disk_items` | int64 | 磁盘缓存条目数 |
| `disk_bytes` | int64 | 磁盘占用字节 |
| `hits` | uint64 | 命中次数 |
| `misses` | uint64 | 未命中次数 |
| `hit_rate` | float64 | 命中率 |

---

### 1.2 视频数据

#### `GET /api/movies/page` — 分页影片列表

| 参数 | 类型 | 必填 | 默认 | 说明 |
|:-----|:-----|:----:|:----:|:------|
| `pg` | int | 否 | 1 | 页码 |
| `size` | int | 否 | 40 | 每页条数 |
| `sort` | string | 否 | "" | 排序：`"score"` / `"time"` / 空 |
| `year` | int | 否 | 0 | 年份筛选 |
| `area` | string | 否 | "" | 地区筛选 |

**返回 `PageResult`：**

```json
{
  "items": [ MovieInfo, ... ],
  "page": 1,
  "size": 40,
  "total": 4551
}
```

---

#### `GET /api/movies/category` — 分类筛选分页

| 参数 | 类型 | 必填 | 默认 | 说明 |
|:-----|:-----|:----:|:----:|:------|
| `g` | string | 否 | "" | 分类名（如"动作""喜剧""纪录片"） |
| `type` | string | 否 | "" | 类型（"电影"/"电视剧"/"综艺"/"动漫"/"纪录片"） |
| `pg` | int | 否 | 1 | 页码 |
| `size` | int | 否 | 40 | 每页条数 |
| `sort` | string | 否 | "" | 排序 |
| `year` | int | 否 | 0 | 年份 |
| `area` | string | 否 | "" | 地区 |

返回同上 `PageResult`。

---

#### `GET /api/movies/types` — 获取所有类型列表

无参数。

**返回：** `["电影","电视剧","纪录片","综艺"]`

---

#### `GET /api/movies/genres` — 获取分类列表

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:------|
| `type` | string | 否 | 按类型过滤，如"电影"只返回该类型下的分类 |

**返回：** `["动作","喜剧","爱情","科幻","恐怖","剧情","战争",...]`

---

#### `GET /api/movies/detail` — 影片详情（含播放源）

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:------|
| `id` | int | **是** | 影片 vod_id |
| `source` | string | 否 | 数据源名称，为空则遍历所有源 |

**返回：** 完整 `MovieInfo`（含 `plays` 播放地址列表）

---

#### `GET /api/movies/search` — 标题模糊搜索

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:------|
| `q` | string | **是** | 搜索关键词 |

**返回：**

```json
{ "items": [ MovieInfo, ... ], "total": 40 }
```

最多返回 40 条。

---

#### `GET /api/movies/search_advanced` — 高级搜索

| 参数 | 类型 | 必填 | 默认 | 说明 |
|:-----|:-----|:----:|:----:|:------|
| `q` | string | 否 | "" | 关键词 |
| `source` | string | 否 | "" | 数据源 |
| `genre` | string | 否 | "" | 分类 |
| `type` | string | 否 | "" | 类型 |
| `pg` | int | 否 | 1 | 页码 |
| `size` | int | 否 | 40 | 每页条数 |

**返回：** `{ "results": [MovieInfo], "total": N }`

---

#### `GET /api/movies/autocomplete` — 搜索自动补全

| 参数 | 类型 | 必填 | 默认 | 说明 |
|:-----|:-----|:----:|:----:|:------|
| `q` | string | **是** | "" | 输入前缀 |
| `size` | int | 否 | 10 | 最大结果数 |

**返回：** `["标题1","标题2","标题3",...]`

---

#### `GET /api/movies/sources` — 数据源列表

无参数。

**返回：** `[{"name":"YingHua","count":5000},{"name":"FeiFan","count":3000},...]`

---

### 1.3 MovieInfo 数据结构（前端最重要）

所有视频列表和详情都返回这个结构，**请以这个为准设计前端**。

| 字段 | 类型 | 示例 | 说明 |
|:-----|:-----|:-----|:------|
| `vod_id` | int | 97228 | **影片ID（唯一标识）** |
| `id` | int | 97228 | 兼容别名，同 vod_id |
| `title` | string | "低智商犯罪" | **影片标题** |
| `coverUrl` | string | "https://img.xx.com/xxx.jpg" | **封面URL** |
| `cover` | string | 同上 | 兼容别名 |
| `year` | int | 2026 | 年份 |
| `area` | string | "大陆" | 地区 |
| `genre` | string | "剧情,悬疑" | 分类/风格（逗号分隔） |
| `director` | string | "刘海波" | 导演 |
| `actors` | string | "王骁,田曦薇,王传君" | 演员列表 |
| `description` | string | "<p>剧情简介...</p>" | 简介（可能含HTML） |
| `score` | string | "7.5" | 评分（字符串） |
| `remark` | string | "更新至第20集" | **备注（集数/画质等）** |
| `source` | string | "FeiFan" | 数据源名称 |
| `type` | string | "电视剧" | **类型：电影/电视剧/综艺/动漫/纪录片** |
| `list_date` | string | "2026-05-12 17:54:01" | 入库日期 |
| `hasUpdate` | bool | false | 是否有更新 |
| `plays` | [ ]PlayGroup | (见下) | 播放源（仅详情接口返回） |

**PlayGroup 结构：**

| 字段 | 类型 | 说明 |
|:-----|:-----|:------|
| `from` | string | 来源标识，如"feifan" |
| `name` | string | **线路名称**，如"线路1""线路2" |
| `urls` | [ ]PlayUrl | 播放列表 |

**PlayUrl 结构：**

| 字段 | 类型 | 示例 | 说明 |
|:-----|:-----|:-----|:------|
| `episode` | string | "第01集" | **集号**（电影通常是"正片"） |
| `url` | string | "https://...m3u8" | **播放地址** |

**完整示例：**

```json
{
  "vod_id": 97228, "id": 97228,
  "title": "低智商犯罪",
  "coverUrl": "https://img.ffzy888.com/upload/vod/20260504-1/xxx.jpg",
  "year": 2026,
  "area": "大陆",
  "genre": "剧情,悬疑",
  "director": "刘海波",
  "actors": "王骁,田曦薇,王传君,朱云峰,张瑞涵",
  "description": "<p>脱离一线刑侦几年的警察张一昂...</p>",
  "score": "0.0",
  "remark": "更新至第20集",
  "source": "FeiFan",
  "type": "电视剧",
  "list_date": "2026-05-12 17:54:01",
  "hasUpdate": false,
  "plays": [
    {
      "from": "feifan",
      "name": "线路1",
      "urls": [
        {"episode": "第01集", "url": "https://vip.ffzy-plays.com/share/xxx1"},
        {"episode": "第02集", "url": "https://vip.ffzy-plays.com/share/xxx2"}
      ]
    },
    {
      "from": "ffm3u8",
      "name": "线路2",
      "urls": [
        {"episode": "第01集", "url": "https://vip.ffzy-plays.com/20260504/xxx1/index.m3u8"},
        {"episode": "第02集", "url": "https://vip.ffzy-plays.com/20260504/xxx2/index.m3u8"}
      ]
    }
  ]
}
```

---

### 1.4 视频代理

| 路径 | 参数 | 说明 |
|:-----|:-----|:------|
| `/api/movies/proxy?url=xxx` | `url`(必填), `ref`(可选) | 视频代理中转。下载远程TS/m3u8，缓存支持，UA伪装，m3u8地址重写为本地代理路径 |
| `/api/movies/play?url=xxx` | `url`(必填) | 302重定向到视频URL |

`/api/movies/proxy` 的 UA 策略：优先 iOS 版（某些CDN只认iPhone UA），3次重试。

---

### 1.5 新分类系统（一二级分类树）

**9个固定一级分类：**

| ID | 名称 | 别名 | 说明 |
|:--:|:-----|:-----|:------|
| 1 | 电影 | movie | 14个子分类 |
| 2 | 电视剧 | tv | 16个子分类 |
| 3 | 短剧 | short | 10个子分类 |
| 4 | 动漫 | anime | 12个子分类 |
| 5 | 综艺 | variety | 10个子分类 |
| 6 | 纪录片 | documentary | 8个子分类 |
| 7 | 少儿 | kids | 6个子分类 |
| 8 | 体育 | sports | 5个子分类 |
| 9 | 资讯 | news | 3个子分类 |

#### 接口

| 路径 | 方法 | 参数 | 说明 |
|:-----|:----:|:-----|:------|
| `/api/category/tree` | GET | 无 | 可见分类树（前端导航用） |
| `/api/category/tree-with-counts` | GET | 无 | 带影片数的分类树 |
| `/api/category/all` | GET | 无 | 全量分类（管理后台） |
| `/api/category/subs` | GET | `pid` | 指定一级下的二级分类 |
| `/api/category/toggle` | GET | `id` | 切换分类显示/隐藏 |
| `/api/category/update` | POST | JSON body | 更新分类属性 |
| `/api/category/add` | POST | JSON body | 添加二级子分类 |
| `/api/category/delete` | GET | `id` | 删除二级子分类 |
| `/api/category/match` | GET | `title/genre/type` | 采集分类匹配测试 |
| `/api/category/stats` | GET | 无 | 分类分布统计 + 分类树 |

**`/api/category/tree` 返回结构：**

```json
[
  {
    "id": 1, "name": "电影", "alias": "movie",
    "subs": [
      {"id": 101, "name": "动作", "alias": "action"},
      {"id": 102, "name": "喜剧", "alias": "comedy"}
    ]
  },
  {
    "id": 2, "name": "电视剧", "alias": "tv",
    "subs": [
      {"id": 201, "name": "都市", "alias": "urban"},
      {"id": 202, "name": "古装", "alias": "costume"}
    ]
  }
]
```

**`/api/category/tree-with-counts` 返回结构：**

```json
[
  {"id": 1, "name": "电影", "count": 1840, "subs": [...]},
  {"id": 2, "name": "电视剧", "count": 3311, "subs": [...]}
]
```

**`/api/category/stats` 返回结构：**

```json
{
  "type_stats": {"电影": 1840, "电视剧": 3311, "纪录片": 400, "综艺": 1},
  "tree": [ 同 tree-with-counts ]
}
```

**`/api/category/match` 返回结构：**

```json
{"cat_id": 1, "cat_name": "电影", "sub_id": 101, "sub_name": "动作"}
```

---

### 1.6 采集管理

| 路径 | 方法 | 说明 |
|:-----|:----:|:------|
| `/api/collect/status` | GET | 采集器当前状态 |
| `/api/collect/run` | POST | 启动采集（异步） |
| `/api/collect/reset` | POST | 重置并全量采集（异步） |
| `/api/collect/nightly_check` | GET | 夜间检查（占位） |

**采集状态返回结构：**

```json
{
  "collecting": false,
  "total_movies": 5552,
  "failed": 0,
  "last_collect": "2026-05-12 18:48:24",
  "progress": 100,
  "progress_text": "完成: 5552 影片",
  "collect_count": 5552,
  "update_count": 40262
}
```

---

### 1.7 直播

| 路径 | 参数 | 说明 |
|:-----|:-----|:------|
| `/api/live` | 无 | 全部直播频道列表 |
| `/api/live/groups` | 无 | 直播分组列表 |
| `/api/live/proxy?url=xxx` | `url`(必填), `ref`(可选) | 直播TS分片代理 |
| `/api/live/play?name=xxx` | `name`(必填) | 获取频道m3u8并重写为本地代理路径 |

**`/api/live/groups` 返回结构：**

```json
[
  {
    "name": "央视",
    "channels": [
      {"name":"CCTV1","url":"https://piccpndali.v.myalicdn.com/audio/cctv1_2.m3u8","group":"央视"},
      {"name":"CCTV2","url":"https://piccpndali.v.myalicdn.com/audio/cctv2_2.m3u8","group":"央视"}
    ]
  },
  {
    "name": "卫视",
    "channels": [
      {"name":"湖南卫视","url":"http://...m3u8","group":"卫视"},
      {"name":"浙江卫视","url":"http://...m3u8","group":"卫视"}
    ]
  }
]
```

**直播播放流程：**

1. 前端调 `/api/live/groups` 获取频道列表
2. 用户选择频道后，前端调 `/api/live/play?name=CCTV1` 获取重写后的 m3u8
3. 前端用 HLS.js 播放返回的 m3u8
4. m3u8 中的 TS 地址已被重写为 `/api/live/proxy?url=xxx`（带 Referer，不缓存）

---

## 2. Java Spring Boot (端口 8080) — 用户业务层

**前端所有请求走 Java**，Java 通过 `/api/cpp/*` 转发到 Go。

> ⚠️ **架构约束：前端不能直连 Go 服务（端口 54567）**
> 所有前端 API 调用必须通过 Java 后端（端口 8080），路径以 `/api/` 开头。
> 即使 `/api/cpp/*` 最终转发到 Go，前端也只认 `/api/cpp/*` 这条路径。
> Go 服务的 `/api/movies/*`、`/api/live/*`、`/api/collect/*` 等接口**仅限 Java 内部调用**，前端不可直接访问。

所有 Java API 的通用返回格式：

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

### 2.1 认证 (`/api/auth`)

#### `POST /api/auth/login` — 登录

```json
// 请求
{"username": "admin", "password": "123456"}

// 返回 data
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "expiresIn": 86400,
  "userId": 1,
  "username": "admin",
  "nickname": "管理员",
  "avatarUrl": null,
  "role": "admin"
}
```

#### `POST /api/auth/register` — 注册

```json
// 请求
{"username": "user1", "password": "pass123", "nickname": "用户一"}

// 返回 data: null（code=200 表示成功）
```

#### `POST /api/auth/refresh` — 刷新Token

```json
// 请求
{"refreshToken": "eyJ..."}

// 返回 data: 同 login 的返回结构
```

---

### 2.2 用户 (`/api/user`)

以下接口需要 `Authorization: Bearer {accessToken}` 头。

| 路径 | 方法 | 参数 | 说明 |
|:-----|:----:|:-----|:------|
| `/api/user/profile` | GET | 无 | 获取当前用户资料 |
| `/api/user/profile` | PUT | `{"nickname":"新昵称"}` | 更新资料 |
| `/api/user/avatar` | POST | multipart/form-data `file` | 上传头像（限2MB） |
| `/api/user/{id}` | GET | path: `id` | 获取指定用户的公开信息 |
| `/api/user/follow/{targetId}` | POST | path: `targetId` | 切换关注/取关 |
| `/api/user/following` | GET | `page/size` | 我的关注列表 |
| `/api/user/fans` | GET | `page/size` | 我的粉丝列表 |
| `/api/user/likes` | GET | `page/size` | 我点赞的视频列表 |
| `/api/user/favorites` | GET | `page/size` | 我收藏的视频列表 |

**`GET /api/user/profile` 返回：**

```json
{
  "id": 1, "username": "admin", "nickname": "管理员",
  "avatarUrl": "/api/avatar/1_xxx.jpg", "role": "admin",
  "createdAt": "2026-01-01T00:00:00"
}
```

---

### 2.3 视频 (`/api/videos`) — 核心接口

#### `GET /api/videos` — 视频分页列表

| 参数 | 类型 | 必填 | 默认 | 说明 |
|:-----|:-----|:----:|:----:|:------|
| `page` | int | 否 | 1 | 页码 |
| `size` | int | 否 | 20 | 每页条数 |
| `category` | string | 否 | null | 分类（如"动作"） |
| `sort` | string | 否 | "hot" | 排序 |
| `year` | int | 否 | 0 | 年份 |
| `area` | string | 否 | null | 地区 |
| `type` | string | 否 | null | 类型（"电影"/"电视剧"等） |

**返回 `VideoListVO`：**

```json
{
  "items": [ VideoItemVO, ... ],
  "page": 1, "size": 20, "total": 100
}
```

**`VideoItemVO` 结构：**

| 字段 | 类型 | 说明 |
|:-----|:-----|:------|
| `cmsVideoId` | Integer | **视频ID（对应Go的vod_id）** |
| `source` | String | 数据源 |
| `title` | String | 标题 |
| `coverUrl` | String | 封面URL |
| `genre` | String | 分类 |
| `score` | String | 评分 |
| `playCount` | Integer | 播放量 |
| `likeCount` | Integer | 点赞数 |
| `collectCount` | Integer | 收藏数 |
| `liked` | Boolean | 当前用户是否已点赞（未登录=null） |
| `favorited` | Boolean | 当前用户是否已收藏（未登录=null） |

---

#### `GET /api/videos/{vodId}` — 视频详情

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:------|
| path `vodId` | int | **是** | 视频ID |
| query `source` | string | 否 | 指定数据源 |

**返回 `VideoDetailVO`：**

```json
{
  "cmsVideoId": 97228, "source": "FeiFan",
  "title": "低智商犯罪",
  "coverUrl": "https://...",
  "year": 2026, "area": "大陆",
  "genre": "剧情,悬疑",
  "director": "刘海波",
  "actors": "王骁,田曦薇...",
  "description": "...",
  "score": "0.0", "remark": "更新至第20集",
  "type": "电视剧",
  "playUrl": "/api/cpp/proxy?url=https%3A%2F%2F...",
  "plays": [
    {"from": "feifan", "name": "线路1", "urls": [{"episode":"第01集","url":"https://..."}]}
  ],
  "playCount": 0, "likeCount": 0, "collectCount": 0,
  "liked": false, "favorited": false,
  "playProgress": 30
}
```

**`stream` 接口：**

`GET /api/videos/stream/{vodId}` — 302重定向到第一个可用的视频播放地址。

---

### 2.4 Go 代理 (`/api/cpp`)

透传到 Go 服务，返回格式统一用 `R<Object>`。

| 路径 | 参数 | 说明 |
|:-----|:-----|:------|
| `/api/cpp/status` | 无 | Go服务状态（ServerSnapshot） |
| `/api/cpp/cache` | 无 | 缓存统计 |
| `/api/cpp/endpoints` | 无 | 端点调用次数 |
| `/api/cpp/collect/status` | 无 | 采集状态 |
| `/api/cpp/collect/run` | 无 | 触发采集 |
| `/api/cpp/collect/reset` | 无 | 重置采集 |
| `/api/cpp/live/groups` | 无 | 直播分组列表 |
| `/api/cpp/live/play` | `name` | 直播播放 |
| `/api/cpp/proxy` | `url` | 视频流代理 |
| `/api/cpp/health` | 无 | 健康检查 |
| `/api/cpp/cache-stats` | 无 | 视频代理缓存统计 |

**`/api/cpp/proxy` 行为说明：**

- **ts/mp4/m4s 等视频片段**：302 重定向到 Go proxy（不经过 Java 内存，解决卡顿）
- **m3u8 清单**：四级降级策略：
  1. Java 缓存命中 → 直接返回
  2. Go proxy（桌面 UA）
  3. Go proxy（iOS UA — 部分 CDN 只认 iPhone）
  4. Java 直连（多种 Referer）
  5. 全部失败 → 302 兜底到 Go proxy

---

### 2.5 分类 (`/api/category`)

透传到 Go 的分类系统，路径和参数完全对应：

| 路径 | 方法 | 参数 |
|:-----|:----:|:-----|
| `/api/category/tree` | GET | 无 |
| `/api/category/tree-with-counts` | GET | 无 |
| `/api/category/stats` | GET | 无 |
| `/api/category/all` | GET | 无 |
| `/api/category/subs` | GET | `pid` |
| `/api/category/toggle` | GET | `id` |
| `/api/category/update` | POST | JSON body |
| `/api/category/add` | POST | JSON body |
| `/api/category/delete` | GET | `id` |
| `/api/category/match` | GET | `title/genre/type` |

---

### 2.6 评论/互动/历史/搜索

#### 评论

| 路径 | 方法 | 参数 | 说明 |
|:-----|:----:|:-----|:------|
| `GET /api/videos/{videoId}/comments` | GET | `videoId`, `page`, `size`, `sort` | 获取评论列表 |
| `POST /api/videos/{videoId}/comments` | POST | path `videoId`, body `{"content":"..."}` | 发表评论（需登录） |
| `POST /api/comments/{commentId}/like` | POST | path `commentId` | 点赞评论 |

**评论返回结构：**

```json
{
  "items": [
    {
      "id": 1, "userId": 1, "videoId": 97228,
      "content": "好看！", "likeCount": 5,
      "userNickname": "管理员", "userAvatarUrl": "/api/avatar/xxx.jpg",
      "isLiked": false, "createdAt": "2026-05-12T10:00:00"
    }
  ],
  "page": 1, "size": 10, "total": 50
}
```

#### 互动

| 路径 | 方法 | 参数 | 说明 |
|:-----|:----:|:-----|:------|
| `POST /api/videos/{videoId}/like` | POST | path `videoId` | 点赞/取消（需登录） |
| `POST /api/videos/{videoId}/favorite` | POST | path `videoId` | 收藏/取消（需登录） |

**返回：** `{"isLiked": true, "likeCount": 10}`

#### 播放历史

| 路径 | 方法 | 参数 | 说明 |
|:-----|:----:|:-----|:------|
| `POST /api/history/report` | POST | `{"videoId":97228,"progress":120,"duration":1800}` | 上报进度（需登录） |
| `GET /api/history` | GET | `page`, `size` | 历史列表（需登录） |
| `GET /api/history/progress/{videoId}` | GET | path `videoId` | 获取某个视频的进度 |
| `DELETE /api/history/{id}` | DELETE | path `id` | 删除某条记录 |

#### 搜索

| 路径 | 方法 | 参数 | 说明 |
|:-----|:----:|:-----|:------|
| `GET /api/search` | GET | `keyword`(必填), `page`, `size` | 搜索（MySQL LIKE 匹配 title） |

**返回：** `UserVideoListVO`，结构与 VideoListVO 相同。

---

## 3. 前端页面规划建议

| 页面 | 核心API | 数据说明 | 推荐布局 |
|:-----|:--------|:---------|:---------|
| **首页/发现** | `GET /api/videos?page=&size=30&sort=time` | items 含 coverUrl/title/remark/score | 横向"正在热映" + 5列海报网格 + 类型Tab筛选 |
| **分类浏览** | `GET /api/videos?type=&category=&page=&size=` | 用 type 和 category 参数筛选 | 分类Tab + 海报网格 |
| **搜索** | `GET /api/videos/search?q=xxx` | 返回 items | 搜索框 + 结果网格 |
| **播放页** | `GET /api/videos/{vodId}` | 返回 plays（多线路+多集） | HLS播放器 + 线路选择 + 选集 + 详情介绍 |
| **直播** | `GET /api/cpp/live/groups` + `/api/cpp/live/play` | groups含分组+频道列表 | 分组侧栏 + 频道网格 + 播放器 |
| **登录/注册** | `POST /api/auth/login` / `/api/auth/register` | 返回 Token | 对话框/页面 |
| **个人中心** | `GET /api/user/profile` | 用户资料 | 页面 |
| **点赞列表** | `GET /api/user/likes` | 含视频封面/标题 | 列表/网格 |
| **收藏列表** | `GET /api/user/favorites` | 同上 | 列表/网格 |
| **播放历史** | `GET /api/history` | 含进度 | 列表+进度条 |
| **管理面板** | `/api/cpp/status` + `/api/cpp/collect/status` + `/api/category/stats` | 状态/采集/分类统计 | 看板布局 |
