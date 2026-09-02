package com.leagueakari.qqbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.config.PushProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * QQ 官方事件通道（WebSocket 客户端，尽力而为骨架）：
 * 连接机器人事件网关，接收群生命周期事件（入群/退群），把群 openid 打到日志提示配置。
 * <p>职责边界：本功能推送是纯主动出站（HTTP OpenAPI），事件通道只用于
 * ① 获取/核验车队群 openid（GROUP_ADD_ROBOT）② 保持机器人在线状态；
 * 事件通道故障不影响发送，也不消费群聊消息。
 * 协议字段以官方文档为准（bot.q.qq.com/wiki）：握手 identify(op2) → hello(op10) 给心跳间隔
 * → 心跳 op1 → 事件 op0。开关 {@code push.ws-enabled} 默认关闭，联调核对后开启。</p>
 */
@Slf4j
@Component
public class QqEventWsClient {

    /** 群相关事件订阅位（GROUP_AND_C2C_EVENT）：官方文档 intents 位定义 */
    private static final int INTENTS_GROUP = 1 << 25;

    /** 重连退避：初始 1s，翻倍至上限 60s */
    private static final long RECONNECT_BASE_MS = 1_000;
    private static final long RECONNECT_MAX_MS = 60_000;

    private final PushProperties pushProperties;
    private final QqEventDispatcher dispatcher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** WS 事件通道开关（默认关闭，联调核对协议后开启） */
    private final boolean wsEnabled;

    /** 事件网关地址（官方域名统一后为 api.bot.qq.com；沙箱/版本差异可覆盖） */
    private final String wsUrl;

    /** 心跳调度（daemon）：hello 帧给出间隔后启动 */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "qq-ws-heartbeat");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean running;
    private volatile WebSocket currentSocket;

    public QqEventWsClient(PushProperties pushProperties, QqEventDispatcher dispatcher,
                           @Value("${push.ws-enabled:false}") boolean wsEnabled,
                           @Value("${push.ws-url:wss://api.bot.qq.com/websocket}") String wsUrl) {
        this.pushProperties = pushProperties;
        this.dispatcher = dispatcher;
        this.wsEnabled = wsEnabled;
        this.wsUrl = wsUrl;
    }

    /** 应用就绪后启动连接（enabled=false 不连接，事件通道纯可选能力） */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!canConnect()) {
            log.warn("QQ WS event channel skipped: push.ws-enabled=false 或机器人凭证未配置（appId/secret）");
            return;
        }
        running = true;
        Thread connector = new Thread(this::connectLoop, "qq-ws-connector");
        connector.setDaemon(true);
        connector.start();
    }

    /**
     * WS 连接前置判定：仅需开关与机器人凭证（appId/secret）。
     * 注意：不要求 push.group-open-id——群 openid 正是靠本通道的入群事件获取的
     * （鸡生蛋：若把 openid 纳入前置，将永远无法通过事件拿到它）
     */
    boolean canConnect() {
        return wsEnabled
                && pushProperties.getAppId() != null && !pushProperties.getAppId().isBlank()
                && pushProperties.getClientSecret() != null && !pushProperties.getClientSecret().isBlank();
    }

    @PreDestroy
    public void stop() {
        running = false;
        heartbeatScheduler.shutdownNow();
        WebSocket socket = currentSocket;
        if (socket != null) {
            socket.abort();
        }
        log.info("QQ WS event channel stopped");
    }

    /**
     * 连接主循环：连接 → 阻塞至断开 → 指数退避 → 重连。
     * listener 的 onClose 完成信号驱动循环前进（阻塞在 future 上，无轮询）
     */
    private void connectLoop() {
        long backoff = RECONNECT_BASE_MS;
        while (running) {
            try {
                CompletableFuture<WebSocket> future = HttpClient.newHttpClient()
                        .newWebSocketBuilder()
                        .buildAsync(URI.create(wsUrl), wsListener());
                WebSocket socket = future.get(15, TimeUnit.SECONDS);
                currentSocket = socket;
                backoff = RECONNECT_BASE_MS; // 连接成功：重置退避
                log.info("QQ WS connected: {}", wsUrl);
                sendIdentify(socket);
                // 阻塞至连接关闭（onClose 完成该 future）
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("QQ WS connector interrupted, exit loop");
                return;
            } catch (Exception e) {
                log.warn("QQ WS connect failed, retry in {}ms: {}", backoff, e.getMessage());
            }
            if (!running) {
                return;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = Math.min(backoff * 2, RECONNECT_MAX_MS);
        }
    }

    /** WS 监听器：处理握手帧、事件帧与断开回调 */
    private WebSocket.Listener wsListener() {
        return new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                webSocket.request(1);
                handleFrame(data.toString());
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                log.info("QQ WS closed: status={}, reason={}", statusCode, reason);
                heartbeatScheduler.shutdownNow();
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                log.warn("QQ WS error: {}", error.getMessage());
            }
        };
    }

    /** 处理一帧：hello(10)/心跳(1)/事件(0)/重连指令(7) */
    private void handleFrame(String payload) {
        try {
            var root = objectMapper.readTree(payload);
            int op = root.path("op").asInt(-1);
            if (op == 10) {
                // hello：拿到心跳间隔（毫秒），按官方规则启动周期心跳
                long interval = root.path("d").path("heartbeat_interval").asLong(0);
                if (interval > 0 && heartbeatScheduler.isShutdown()) {
                    scheduleHeartbeat(interval);
                }
                return;
            }
            if (op == 0) {
                QqEventDispatcher.GroupEvent event = dispatcher.parse(payload);
                if (event == null) {
                    return; // 非关注事件（群消息等）：不打扰
                }
                if ("GROUP_ADD_ROBOT".equals(event.type())) {
                    log.info("机器人已加入群 group_openid={}，请将 push.group-open-id 配置为该值",
                            event.groupOpenId());
                } else {
                    log.warn("机器人被移出群 group_openid={}，局后播报将无法送达",
                            event.groupOpenId());
                }
            }
            // op 1（心跳回执）/ op 7（要求重连，由断开机制自然处理）：无需额外动作
        } catch (Exception e) {
            log.warn("QQ WS frame handling failed: {}", e.getMessage());
        }
    }

    /** 握手：identify（op2），token 由 appId 与 clientSecret 拼接（官方格式联调核对） */
    private void sendIdentify(WebSocket socket) {
        Map<String, Object> d = Map.of(
                "token", pushProperties.getAppId() + "." + pushProperties.getClientSecret(),
                "intents", INTENTS_GROUP,
                "shard", java.util.List.of(0, 1));
        try {
            socket.sendText(objectMapper.writeValueAsString(Map.of("op", 2, "d", d)), true)
                    .thenRun(() -> log.info("QQ WS identify sent"));
        } catch (Exception e) {
            log.warn("QQ WS identify failed: {}", e.getMessage());
        }
    }

    /** 周期心跳（op1）：hello 帧给间隔后启动，断开时由 onClose 停掉调度器 */
    private void scheduleHeartbeat(long intervalMs) {
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            WebSocket socket = currentSocket;
            if (socket != null && !socket.isInputClosed()) {
                socket.sendText("{\"op\":1,\"d\":null}", true);
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }
}
