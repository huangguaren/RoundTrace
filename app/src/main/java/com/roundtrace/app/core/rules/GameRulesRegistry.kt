package com.roundtrace.app.core.rules

import com.roundtrace.app.core.model.GameConfig
import com.roundtrace.app.core.model.GameType

/**
 * 规则集注册表（ARCHITECTURE §3）。
 *
 * 规则版本化的意义：`rulesetVersion` 参与解析，历史对局永远用它当时的版本重算，
 * 规则升级不会改写旧账（TDD §3.1-4）。
 */
interface GameRulesRegistry {

    /**
     * 按 id + 版本解析规则实现。
     *
     * 未知 `rulesetId` / `version` 必须抛**带明确信息**的 [IllegalArgumentException]，
     * 不得静默回落到默认实现（否则历史对局会被错误规则重算）。
     */
    fun rulesFor(rulesetId: String, version: Int): GameRules

    /** 按配置解析（即 `rulesFor(config.rulesetId, config.rulesetVersion)`）。 */
    fun rulesFor(config: GameConfig): GameRules

    /** 当前支持的规则集清单（供设置页展示）。 */
    fun supportedRulesets(): List<RulesetDescriptor>
}

/** 规则集描述（ARCHITECTURE §3）。 */
data class RulesetDescriptor(
    val rulesetId: String,
    val version: Int,
    val displayName: String,
    val gameType: GameType,
    val presetIds: List<String>
)
