package com.roundtrace.app.di

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.feature.claim.ClaimViewModel
import com.roundtrace.app.feature.create.CreateMatchViewModel
import com.roundtrace.app.feature.detail.MatchDetailViewModel
import com.roundtrace.app.feature.home.HomeViewModel
import com.roundtrace.app.feature.record.RecordViewModel
import com.roundtrace.app.feature.replay.ReplayViewModel
import com.roundtrace.app.feature.reveal.RevealViewModel
import com.roundtrace.app.feature.settings.SettingsViewModel

/**
 * 各 ViewModel 的工厂（TASKS.md T37②）。
 *
 * 手动 DI 的代价就在这里：**新增 ViewModel 必须在此手工登记**（无编译期注入图校验）。
 * 好处是依赖关系一眼可见，且不需要 KSP/KAPT 的注解处理器链路。
 */
object ViewModelFactories {

    fun home(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
        initializer { HomeViewModel(repository = container.matchRepository) }
    }

    fun create(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            CreateMatchViewModel(
                repository = container.matchRepository,
                settingsRepository = container.settingsRepository,
                rules = container.avalonRules,
                clock = container.clock
            )
        }
    }

    fun record(container: AppContainer, matchId: MatchId): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                RecordViewModel(
                    matchId = matchId,
                    repository = container.matchRepository,
                    gameRulesRegistry = container.gameRulesRegistry,
                    reducer = container.reducer,
                    rules = container.avalonRules,
                    eventFactory = container.eventFactory
                )
            }
        }

    fun claim(container: AppContainer, matchId: MatchId): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                ClaimViewModel(
                    matchId = matchId,
                    repository = container.matchRepository,
                    eventFactory = container.eventFactory
                )
            }
        }

    fun reveal(container: AppContainer, matchId: MatchId): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                RevealViewModel(
                    matchId = matchId,
                    repository = container.matchRepository,
                    reducer = container.reducer,
                    eventFactory = container.eventFactory
                )
            }
        }

    fun replay(container: AppContainer, matchId: MatchId): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                ReplayViewModel(
                    matchId = matchId,
                    repository = container.matchRepository,
                    knowledgeProvider = container.knowledgeProvider,
                    analyzer = container.analyzer
                )
            }
        }

    fun settings(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            SettingsViewModel(
                settingsRepository = container.settingsRepository,
                importExport = container.importExport
            )
        }
    }

    fun matchDetail(container: AppContainer, matchId: MatchId): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                MatchDetailViewModel(
                    matchId = matchId,
                    repository = container.matchRepository,
                    importExport = container.importExport
                )
            }
        }
}
