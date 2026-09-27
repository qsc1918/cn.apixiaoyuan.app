package cn.apixiaoyuan.app.core.network.api

import cn.apixiaoyuan.app.core.model.ExerciseHomepageData
import cn.apixiaoyuan.app.core.network.BASE_LEO
import cn.apixiaoyuan.app.core.network.BaseUrl
import cn.apixiaoyuan.app.core.network.GsonConverter
import cn.apixiaoyuan.app.core.network.NotNullAndValid
import retrofit2.http.GET

/**
 * 练习「星级」接口（`/leo-star/...`）。
 *
 * 独立成一个接口而不塞进 [LeoExerciseCommonLegacyApiService]：
 * 那条链路是「今日练习上报（旧版）」，这条是「练习星级首页（读数）」，
 * 生命周期与用途不同（前者写、后者读）。
 *
 * ## 为什么需要它（2026-09-27）
 *
 * 刷分页此前用 `rank/pre-fetch.curWeekScore`（**周排行榜**分数）当「当前分数」，
 * 但 `postSavedExp`（刷分上报）记的是**练习经验**，两者不是一回事 ——
 * 上报成功后 `curWeekScore` 不动，UI 就显得「分数显示异常」。
 * 正确读数在 `/leo-star/android/exercise/homepage` 的 `curWeekExp`。
 *
 * smali 出处：`LeoMathApiService` 里有该 GET（`/leo-star/android/exercise/homepage`），
 * 注解组合 `@GsonConverter + @NotNullAndValid + @GET`。
 */
interface ExerciseStarApiService {

    /**
     * 练习星级首页：连续天数 / 本周经验 / 今日积分 / 排名档位。
     *
     * GET `/leo-star/android/exercise/homepage`
     */
    @BaseUrl(BASE_LEO)
    @GsonConverter
    @NotNullAndValid
    @GET("/leo-star/android/exercise/homepage")
    suspend fun getHomepage(): ExerciseHomepageData
}