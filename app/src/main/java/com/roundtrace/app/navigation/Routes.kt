package com.roundtrace.app.navigation

/**
 * 全部路由常量与参数名的集中定义（TASKS.md T09⑤：禁止字符串魔法值散落）。
 *
 * 采用**字符串路由**而非 type-safe 路由（ARCHITECTURE §9.3-19）：
 * 少引入一条 kotlinx-serialization 与 Navigation 的耦合，降低首次构建风险。
 */
object Routes {

    /** 启动页（splash），显示 1.5s 后跳主页。 */
    const val SPLASH: String = "splash"

    // —— 三个主 Tab ——
    const val HOME: String = "home"
    const val RECORDS: String = "records"
    const val REVIEW: String = "review"

    // —— 工作流页面（M2+ 逐个实现，本轮不注册为可导航页面）——
    const val CREATE_MATCH: String = "create"
    const val RECORD: String = "record"
    const val CLAIM: String = "claim"
    const val REVEAL: String = "reveal"
    const val DETAIL: String = "detail"
    const val SETTINGS: String = "settings"

    /** 单场对局的复盘页（M4/T58）。 */
    const val REPLAY: String = "replay"

    /** 所有路由共用的参数名 */
    const val ARG_MATCH_ID: String = "matchId"
    const val ARG_EVENT_ID: String = "eventId"

    /** 主 Tab 路由集合，用于底部导航选中态判定 */
    val topLevelRoutes: List<String> = listOf(HOME, RECORDS, REVIEW)

    /** 带参数的完整路由模板 */
    const val RECORD_PATTERN: String = "$RECORD/{$ARG_MATCH_ID}"
    const val CLAIM_PATTERN: String = "$CLAIM/{$ARG_MATCH_ID}"
    const val REVEAL_PATTERN: String = "$REVEAL/{$ARG_MATCH_ID}"
    const val DETAIL_PATTERN: String = "$DETAIL/{$ARG_MATCH_ID}"
    const val REPLAY_PATTERN: String = "$REPLAY/{$ARG_MATCH_ID}"

    /** 构造带参数的导航目标，避免调用点拼字符串。 */
    fun record(matchId: String): String = "$RECORD/$matchId"

    fun claim(matchId: String): String = "$CLAIM/$matchId"

    fun reveal(matchId: String): String = "$REVEAL/$matchId"

    fun detail(matchId: String): String = "$DETAIL/$matchId"

    fun replay(matchId: String): String = "$REPLAY/$matchId"

    /** 判断某个路由是否属于主 Tab（含路由模板本身）。 */
    fun isTopLevel(route: String?): Boolean = route != null && topLevelRoutes.contains(route)
}
