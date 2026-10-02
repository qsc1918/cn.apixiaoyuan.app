package cn.apixiaoyuan.app.core.network

import cn.apixiaoyuan.app.core.network.api.LeoEnglishExerciseWritingApiService
import cn.apixiaoyuan.app.core.network.api.ExerciseStarApiService
import cn.apixiaoyuan.app.core.network.api.LeoExerciseCommonLegacyApiService
import cn.apixiaoyuan.app.core.network.api.LeoGatewayService
import cn.apixiaoyuan.app.core.network.api.LeoMathApiService
import cn.apixiaoyuan.app.core.network.api.LeoOralApiService
import cn.apixiaoyuan.app.core.network.api.LeoPoemsParadiseApiService
import cn.apixiaoyuan.app.core.network.api.LeoProfileApiService
import cn.apixiaoyuan.app.core.network.api.LeoShareApiService
import cn.apixiaoyuan.app.core.network.api.LeoUserApiService
import cn.apixiaoyuan.app.core.network.api.ShepherdApiService
import cn.apixiaoyuan.app.core.network.api.SubAccountApiService
import cn.apixiaoyuan.app.core.network.api.YtkApiService
import cn.apixiaoyuan.app.core.network.api.YtkUserCenterApiService

/**
 * 全部 ApiService 的集中出口，替代原版的 `sp/n`。
 *
 * 账号域（ape-api.yuanfudao.com）与主域（xyks.yuanfudao.com）的 Service
 * 分开列，一眼能看出某个接口挂在哪套域名上。
 *
 * 注意：[LeoGatewayService] 名字像账号域，实际挂主域 ——
 * 从 `mg/h.smali` 方法链与 `sp/n.smali` 服务定位两处交叉确证。
 *
 * 注意：`by lazy` 的首次访问会触发 [RetrofitFactory.leo] / [RetrofitFactory.ytk]，
 * 因此 [RetrofitFactory.init] 必须在任何 Service 被取用之前调用（在 Application.onCreate）。
 */
object ServiceLocator {

    // ---- 账号域（ape-api.yuanfudao.com）----

    val ytkApi: YtkApiService by lazy {
        RetrofitFactory.ytk(YtkApiService::class.java)
    }

    // ---- 主域（xyks.yuanfudao.com）----

    val gateway: LeoGatewayService by lazy {
        RetrofitFactory.leo(LeoGatewayService::class.java)
    }

    val profile: LeoProfileApiService by lazy {
        RetrofitFactory.leo(LeoProfileApiService::class.java)
    }

    val user: LeoUserApiService by lazy {
        RetrofitFactory.leo(LeoUserApiService::class.java)
    }

    val exerciseLegacy: LeoExerciseCommonLegacyApiService by lazy {
        RetrofitFactory.leo(LeoExerciseCommonLegacyApiService::class.java)
    }

    /**
     * 练习星级（`/leo-star/android/exercise/homepage`）。
     *
     * 刷分页的「当前分数」用它 —— `postSavedExp` 记的是**练习经验**，
     * 对应 `curWeekExp`；`rank/pre-fetch` 那个 `curWeekScore` 是**排行榜**分数，
     * 刷分不会动它（详见 [cn.apixiaoyuan.app.core.model.ExerciseHomepageData]）。
     */
    val exerciseStar: ExerciseStarApiService by lazy {
        RetrofitFactory.leo(ExerciseStarApiService::class.java)
    }

    /** 数学练习（出题链路：`/leo-math/android/exams/exercises/type/{type}`）。 */
    val math: LeoMathApiService by lazy {
        RetrofitFactory.leo(LeoMathApiService::class.java)
    }

    /** 口算出题与提交（`/leo-math/android/exams`）。 */
    val oral: LeoOralApiService by lazy {
        RetrofitFactory.leo(LeoOralApiService::class.java)
    }

    val englishExercise: LeoEnglishExerciseWritingApiService by lazy {
        RetrofitFactory.leo(LeoEnglishExerciseWritingApiService::class.java)
    }

    val poemsParadise: LeoPoemsParadiseApiService by lazy {
        RetrofitFactory.leo(LeoPoemsParadiseApiService::class.java)
    }

    /**
     * ~~PK 秒结算/循环/并发~~ **已移除**（2026-10-02）。
     *
     * PK 链路改走 [cn.apixiaoyuan.app.core.pk.PkRawApi]（裸 OkHttp + 专用协议表
     * [cn.apixiaoyuan.app.core.pk.PkProtocol]），不再经过本文件的 Retrofit。
     *
     * 原因：这里挂的 `CommonQueryInterceptor` / `HeaderInterceptor` /
     * `AuthInterceptor` 全是**主域**纪律（`_productId=611` + `android<本机SDK>`
     * + `vendor=UC` + `isBackground=0` + `Leo/…` UA + `YFD_U`），而 PK 要的是
     * 另一套（`611` 不带 `_appId` + `android35` + `fenbi` + 不带 `isBackground`
     * + H5 WebView UA + 不带 `YFD_U`）。与其在全局拦截器里塞「如果是 PK 就跳过」
     * 的分支把两套协议搅在一起，不如给 PK 一条干净的专用 client。
     */

    val shepherd: ShepherdApiService by lazy {
        RetrofitFactory.leo(ShepherdApiService::class.java)
    }

    val share: LeoShareApiService by lazy {
        RetrofitFactory.leo(LeoShareApiService::class.java)
    }

    // ---- 账号域管理链（与 ytkApi 同域，路径不重叠）----

    val ytkUserCenter: YtkUserCenterApiService by lazy {
        RetrofitFactory.ytk(YtkUserCenterApiService::class.java)
    }

    /**
     * 子账号（「切换宝贝学习账号」）。
     *
     * **注意**：这个接口同时挂两套域名 —— `batchGet` / `switch` 走主域，
     * `registerSonSubUser` / `deregisterSubUser` / `subDeregister` 走账号域。
     * 靠方法上的 `@BaseUrl` 注解分流，所以实例从哪套 Retrofit 建都行
     * （这里用主域，因为两个主域方法更常被调用）。
     */
    val subAccount: SubAccountApiService by lazy {
        RetrofitFactory.leo(SubAccountApiService::class.java)
    }
}
