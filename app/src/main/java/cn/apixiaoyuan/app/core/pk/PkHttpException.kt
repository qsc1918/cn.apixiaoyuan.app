package cn.apixiaoyuan.app.core.pk

/**
 * PK 接口的 HTTP 错误（带状态码 + 响应体）。
 *
 * ## 为什么要单独一个类型（2026-09-27，待办 16）
 *
 * Retrofit 在非 2xx 时抛的是 `retrofit2.HttpException`，它的 `message` 形如
 * `HTTP 403 Client Error` —— **响应体被丢掉了**。而 PK 两个最关键的失败原因
 * 恰恰只写在响应体里（真机实测）：
 *
 * | 状态码 | 响应体 | 真因 |
 * |---|---|---|
 * | 403 | `{"status":403,"message":"error"}` | 提交接口的**独立频控**（窗口约 10 分钟级） |
 * | 400 | `{"status":400,"message":"请求过于频繁"}` | 出题接口频控 |
 *
 * 旧代码把这些统一成 `HTTP 403`，界面与日志都看不出「是频控」还是「body 错了」，
 * 于是引擎会按普通失败**立即重试** —— 而重试只会把频控窗口推得更长。
 *
 * 本类把 code 与 body 原样带出来，让 [PkBattleEngine] 能区分
 * 「该等」与「不该等」。
 *
 * @param code  HTTP 状态码
 * @param body  响应体原文（已截断），用于判定是否频控
 */
class PkHttpException(
    val code: Int,
    val body: String,
) : Exception("HTTP $code：${body.take(160)}") {

    /**
     * 是否服务端频控 / 风控。
     *
     * 判定依据全部来自真机实测的响应体措辞：
     *  - `429` —— 标准频控码，无条件算；
     *  - `400` / `403` 且 body 含「频繁」或 `"error"` 之外的频控标记 ——
     *    实测 400 的频控响应体是 `请求过于频繁`；
     *  - `403` —— 提交接口实测就是**只有**这个码，body 固定 `{"status":403,"message":"error"}`，
     *    与频控窗口（约 10 分钟）严格对应，因此**无条件视作频控**。
     */
    val isRateLimited: Boolean
        get() = code == 429 ||
            code == 403 ||
            body.contains("频繁") ||
            body.contains("too many", ignoreCase = true) ||
            body.contains("rate limit", ignoreCase = true)

    /** 是否「提交内容被拒」而非频控 —— 目前无法从响应体区分，一律归入 [isRateLimited]。 */
    val isRejected: Boolean get() = !isRateLimited
}
