package cn.apixiaoyuan.app.core.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import cn.apixiaoyuan.app.core.design.theme.PageTransitionPrefs
import cn.apixiaoyuan.app.core.navigation.transition.appNavTransition
import cn.apixiaoyuan.app.core.navigation.transition.rememberAppNavEffects
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

/**
 * 应用导航宿主 —— **miuix-nav 的 `NavDisplay`**（不是 androidx `NavHost`）。
 *
 * ## 这是一个「单一 NavDisplay」
 *
 * 全应用只有一个返回栈、一个 `NavDisplay`。原因见
 * [cn.apixiaoyuan.app.MainActivity] 的 KDoc：miuix 的 `NavDisplay` 内部自己注册
 * 预测性返回（`PredictiveBackHandlerWithSessions(enabled = backStack.size > 1)`），
 * 四个 Tab 各挂一个会互相抢手势。
 *
 * ## 根 entry 由调用方提供
 *
 * 四个 Tab 根路由**都必须注册 entry**（栈里出现的 key 必须有渲染者），
 * 但真正占屏幕的是 `RouteHome` 这个「根 entry」——里面放 `HorizontalPager`，
 * 由 pager 负责切 Tab（同层平移，不压栈）。`RouteApi` / `RouteRepl` /
 * `RouteSettings` 的 entry 只在「栈里恰好是它们」时被渲染（正常操作不会发生，
 * 因为切 Tab 走 pager；保留它们是为了栈状态被恢复时也安全）。
 *
 * @param navController 唯一的导航器（见 [rememberAppNavController]）。
 * @param root 根内容（四个 Tab 的 pager）。
 */
@Composable
fun AppNavHost(
    navController: AppNavController,
    root: @Composable () -> Unit,
) {
    // transition / effects 都随设置项实时切换：两者内部读的是 Compose 可观察
    // 状态（PageTransitionPrefs.animation），设置页改一下这里就重组 —— 不需要重启。
    val transition = appNavTransition(PageTransitionPrefs.animation)
    val effects = rememberAppNavEffects()
    val backdropColor = MaterialTheme.colorScheme.surfaceContainer

    CompositionLocalProvider(LocalAppNavController provides navController) {
        NavDisplay(
            backStack = navController.backStack,
            onBack = { navController.popBackStack() },
            transition = transition,
            effects = effects,
            modifier = Modifier
                .fillMaxSize()
                // 转场层底色：被覆盖页与进场页之间不能露黑（圆角裁切时会看到
                // 边角），用主题的 surfaceContainer —— 与二级页 Scaffold 同色。
                .background(backdropColor),
        ) {
            entry<RouteHome> { root() }
            // 接口控制台（2026-09-27，待办 9）。
            //
            // 此前这里是 `entry<RouteApi> { root() }` —— 那是错的：`root()` 是整个
            // 四 Tab 的 pager，从首页快捷入口点进来会把 pager 再套一层（两个底栏、
            // 两份内容），所以这条路由实际上**从没被用作页面入口**，`ApiScreen`
            // 也就一直不可达（只能从「功能」Tab 里的其它页面绕）。
            // 现在改成像其它二级页一样渲染自己的页面，并给一个左滑返回。
            entry<RouteApi>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                cn.apixiaoyuan.app.feature.api.ApiScreen(navController)
            }
            // RouteRepl / RouteSettings 只作「返回栈状态被系统恢复」时的安全兜底：
            // 正常操作不会把这两个 key 压进栈（切 Tab 走 pager，不压栈），
            // 保留 `root()` 是为了任何情况下栈里都有个能渲染的根。
            entry<RouteRepl> { root() }
            entry<RouteSettings> { root() }
            entry<RouteSamples>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                cn.apixiaoyuan.app.feature.samples.SamplesScreen(navController)
            }
            entry<RoutePk>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                cn.apixiaoyuan.app.feature.pk.PkScreen(navController)
            }
            entry<RouteExercise>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                cn.apixiaoyuan.app.feature.exercise.ExerciseScreen(navController)
            }
            entry<RouteExam>(swipeDismiss = NavSwipeDirection.LeftToRight) { key ->
                cn.apixiaoyuan.app.feature.exercise.ExamScreen(
                    navController = navController,
                    keypointId = key.keypointId,
                    limit = key.limit,
                    title = key.title,
                )
            }
            entry<RouteLogin>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                cn.apixiaoyuan.app.feature.login.LoginScreen(navController)
            }
            entry<RouteOldSimian>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                cn.apixiaoyuan.app.feature.oldsimian.OldSimianScreen(navController)
            }
            entry<RouteExercisePump>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                cn.apixiaoyuan.app.feature.exercise.ExercisePumpScreen(navController)
            }
            entry<RouteGrind>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                cn.apixiaoyuan.app.feature.grind.GrindScreen(navController)
            }
            entry<RouteScorePump>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                cn.apixiaoyuan.app.feature.oldsimian.ScorePumpScreen(navController)
            }
            entry<RoutePkGrind>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                cn.apixiaoyuan.app.feature.pk.PkGrindScreen(navController)
            }
            // 「账号分数 / 任务」—— 刷分区的只读观测台（★ 2026-09-30）
            entry<cn.apixiaoyuan.app.core.navigation.RouteAccountStats>(
                swipeDismiss = NavSwipeDirection.LeftToRight,
            ) {
                cn.apixiaoyuan.app.feature.grind.AccountStatsScreen(navController)
            }
            entry<RouteAccount>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                cn.apixiaoyuan.app.feature.account.AccountScreen(navController)
            }
        }
    }
}