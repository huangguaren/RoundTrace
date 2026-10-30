package com.roundtrace.app.core.rules

import com.roundtrace.app.core.model.GameConfig
import com.roundtrace.app.core.model.GameType
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.rules.avalon.AvalonReducer
import com.roundtrace.app.core.rules.avalon.AvalonRules

/**
 * 规则注册表实现（TASKS.md T21①）。
 *
 * 版本化解析：只有 `(avalon.standard, 1)` 被注册。未知 id / 版本**抛带明确信息的异常**，
 * 绝不静默回落到默认实现——否则历史对局会被错误版本的规则重算。
 */
class DefaultGameRulesRegistry : GameRulesRegistry {

    private val avalonRules: AvalonRules = AvalonRules()

    /** 当前注册的 (rulesetId, version) → 规则实现。 */
    private val registry: Map<Pair<String, Int>, GameRules> = mapOf(
        (AvalonPreset.RULESET_ID to AvalonPreset.RULESET_VERSION) to avalonRules
    )

    override fun rulesFor(rulesetId: String, version: Int): GameRules {
        return registry[rulesetId to version] ?: throw IllegalArgumentException(
            "未注册的规则集：rulesetId=\"$rulesetId\", version=$version；" +
                "当前支持 ${registry.keys.joinToString { "${it.first}@${it.second}" }}"
        )
    }

    override fun rulesFor(config: GameConfig): GameRules =
        rulesFor(config.rulesetId, config.rulesetVersion)

    override fun supportedRulesets(): List<RulesetDescriptor> = listOf(
        RulesetDescriptor(
            rulesetId = AvalonPreset.RULESET_ID,
            version = AvalonPreset.RULESET_VERSION,
            displayName = AvalonPreset.DISPLAY_NAME,
            gameType = GameType.AVALON,
            presetIds = AvalonPreset.supportedPlayerCounts.map { AvalonPreset.presetId(it) }
        )
    )

    /** 阿瓦隆 reducer（进程内复用同一个无状态实例）。 */
    val avalonReducer: GameReducer = AvalonReducer(avalonRules)

    /** 直接取阿瓦隆规则对象（需要 `validateTeamDraft` 等非接口方法时使用）。 */
    val avalonRuleSet: AvalonRules
        get() = avalonRules
}
