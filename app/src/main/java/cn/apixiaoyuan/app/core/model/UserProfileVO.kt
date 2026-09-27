package cn.apixiaoyuan.app.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 账号域用户资料（`GET /profile/android/user-info`，`ape-api.yuanfudao.com`）。
 *
 * ## 为什么用它回填「当前用户信息」
 *
 * PK H5 的 `getUserInfo` 桥读 [cn.apixiaoyuan.app.core.session.SessionStore] 的
 * `currentNickname` / `currentAvatarUrl` / `grade`。这些此前只在
 * `fetchSubAccounts`（`batchGet`，需设备链）成功时才回填 —— 而 `batchGet` 是
 * 417，于是 H5 首屏一直显示「我 / 0 级」= 未登录态。
 *
 * 本接口**不需要设备链**（2026-09-27 python 实测 200，返回
 * `{"userId":511467407,"nickname":"...","avatarId":"...","grade":13}`），
 * 是回填用户信息的可靠通道。
 *
 * 字段取自真实响应；只保留本项目用到的（nickname / avatarId / grade）。
 */
@Serializable
data class UserProfileVO(
    @SerialName("userId") val userId: Long = 0L,
    @SerialName("nickname") val nickname: String? = null,
    @SerialName("avatarId") val avatarId: String? = null,
    @SerialName("grade") val grade: Int = 0,
) {
    /**
     * 头像完整 URL。
     *
     * `avatarId` 是文件名（如 `39YxTlH0kJRbCzYuO3sa3r.jpg`），原版头像 CDN 前缀
     * 取自 PK H5 接口返回的 `avatarUrl`（形如
     * `https://leo-online.fbcontent.cn/leo-gallery/<avatarId>`）。
     */
    val avatarUrl: String?
        get() = avatarId?.takeIf { it.isNotBlank() }
            ?.let { "https://leo-online.fbcontent.cn/leo-gallery/$it" }
}