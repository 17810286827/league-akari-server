package com.leagueakari.util;

/**
 * 客户端断开异常识别工具
 * <p>SSE/流式响应场景下，客户端关闭页面、刷新或网络断开都会导致服务端写入失败
 * （Broken pipe / Connection reset / ClientAbortException / AsyncRequestNotUsableException）。
 * 这类异常是<b>预期现象</b>而非服务端故障，调用方应据此降级日志（INFO/WARN、无堆栈）
 * 并终止流式推送，避免同一断开事件在多层重复打出 ERROR 堆栈刷屏。</p>
 */
public final class ClientDisconnectDetector {

    /** 断开类异常类名片段（避免与 Tomcat/Spring 强耦合，按类名识别） */
    private static final String[] DISCONNECT_CLASS_NAMES = {
            "ClientAbortException",               // Tomcat：对端已关闭连接
            "AsyncRequestNotUsableException"      // Spring 6.1：异步响应已不可用（SSE 输出流失效）
    };

    /** 断开类 IOException 消息片段 */
    private static final String[] DISCONNECT_MESSAGES = {
            "Broken pipe",                        // Linux：写入已关闭的 socket
            "Connection reset",                   // 连接被对端重置
            "connection was aborted",             // Tomcat 包装后的描述
            "你的主机中的软件中止了一个已建立的连接"    // Windows 中文系统下 Broken pipe 的本地化消息
    };

    private ClientDisconnectDetector() {
        // 工具类：禁止实例化
    }

    /**
     * 判断异常（含 cause 链）是否由客户端断开连接引起
     *
     * @param throwable 待检查的异常（null 返回 false）
     * @return true 表示客户端断开，调用方应按预期事件处理（降级日志 + 停止推送）
     */
    public static boolean isClientDisconnect(Throwable throwable) {
        // 沿 cause 链向下查（最多 8 层防御循环引用），任意一层命中即认定断开
        Throwable cur = throwable;
        for (int depth = 0; cur != null && depth < 8; depth++) {
            if (matchesClassName(cur) || matchesMessage(cur)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 按异常类名识别断开类异常 */
    private static boolean matchesClassName(Throwable t) {
        String name = t.getClass().getName();
        for (String fragment : DISCONNECT_CLASS_NAMES) {
            if (name.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** 按 IOException 消息识别断开（不同 OS/容器本地化文案不同） */
    private static boolean matchesMessage(Throwable t) {
        String message = t.getMessage();
        if (message == null) {
            return false;
        }
        for (String fragment : DISCONNECT_MESSAGES) {
            if (message.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}