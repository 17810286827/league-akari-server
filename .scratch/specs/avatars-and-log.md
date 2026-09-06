# Spec: 英雄头像展示与日志降噪

> 术语见后端 `CONTEXT.md`。头像与 AI 加厚（独立 spec）技术互不依赖，可并行。

## Problem Statement

赛季报告的英雄池漂移、榜单中心绝活榜、组合 tab 常用阵容、复盘/诊断面板里
的英雄只有文字（中文名），辨识度差——"只看文字不太好"。另外后端日志被公网
扫描器的 404 探测刷屏（key.pem/.ssh/config 等密钥扫描），有用信息被淹没。

## Solution

四处英雄展示加 Data Dragon 头像：后端各响应补 championId，前端用项目已有的
图标 URL 能力渲染头像（尺寸/圆角统一）。日志降噪：静态资源 404（NoResourceFoundException）
降为 DEBUG，API 路径 404 保持 WARN。

## User Stories

1. 作为车队成员，赛季报告英雄池漂移区里每个英雄带头像，一眼认出英雄（不用读中文名）。
2. 作为车队成员，榜单中心绝活榜的英雄分组标题带头像。
3. 作为车队成员，组合 tab 常用阵容里每个成员名旁显示其该局英雄头像（阵容接口补英雄数据）。
4. 作为车队成员，AI 复盘的击杀事件与转折点涉及成员带英雄头像（面板响应补 championId）。
5. 作为车队成员，对局诊断面板的玩家行带英雄头像。
6. 作为维护者，后端日志不再被扫描器 404 刷屏，有用 WARN 可见。
7. 作为维护者，API 层的异常 404（真 bug 信号）仍保持 WARN 不漏。

## Implementation Decisions

- **数据链路**：赛季报告 ChampionCount 补 championId；绝活榜 BoardEntry 已有
  championId（确认透传即可）；常用阵容 LineupStats 补成员×局英雄（阵容聚合
  时记录出现过的英雄）；复盘 KillEvent/InvolvedPlayer 与诊断 PlayerDiagnosis
  补 championId。
- **前端渲染**：复用项目既有 Data Dragon 图标 URL 能力（game-resource 模块），
  头像统一小尺寸圆角样式；缺失 ID 时回退文字（防御旧数据）。
- **日志降噪**：GlobalExceptionHandler 的 NoResourceFoundException 处理由 WARN
  降 DEBUG（公网扫描常态）；仅 /api/** 前缀的 404 保持 WARN。
- **无 schema 变更**（只加响应字段，不动表）。

## Testing Decisions

- 好的测试只测外部行为：后端响应含 championId（既有 service 测试断言扩展）；
  前端组件测试断言头像元素渲染与缺失回退。
- **后端**：SeasonReportServiceTest/DuoStatsServiceTest/ReplayServiceTest/
  DiagnosisServiceTest 各补 championId 断言。
- **前端**：SeasonReportView/LeaderboardsView/ReplayPanel/DiagnosePanel 既有
  组件测试补头像渲染断言。
- **日志**：GlobalExceptionHandlerTest 既有接缝补"静态 404 不再 WARN、
  API 404 仍 WARN"用例。

## Out of Scope

- 队列模式图标、召唤师头像（profileIcon）——非本需求。
- 防火墙/端口加固（运维操作，建议用户在云控制台做：限制 8002 来源 IP）。
- 头像悬停大图/英雄详情跳转。

## Further Notes

- 日志问题的根因说明（已告知用户）：公网扫描器探测密钥/配置文件，
  全部 404 = 无入侵迹象；最有效加固是云防火墙限制来源 IP，代码层只做降噪。
- 常用阵容的英雄数据定义：阵容内成员在该组合局中最常使用的英雄（聚合时
  记录 member→champion 次数取众数）。
