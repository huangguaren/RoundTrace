package com.roundtrace.app.feature

import com.roundtrace.app.core.data.MatchRepository
import com.roundtrace.app.core.data.MatchStatusFilter
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MatchSummary
import com.roundtrace.app.feature.home.HomeFilter
import com.roundtrace.app.feature.home.HomeUiState
import com.roundtrace.app.feature.home.HomeViewModel
import com.roundtrace.app.knowledge.AvalonScenario
import com.roundtrace.app.util.FakeMatchRepository
import com.roundtrace.app.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * NX-03-RETRY：首页查询失败必须可恢复（PRD §4 T-C01…T-C06）。
 *
 * 缺陷复现（修复前的形态）：`.catch` 挂在整条组合流末尾，
 * 上游异常会终止 `stateIn` 的上游协程，`retry()` 只把 trigger +1 却再没人收集，
 * 于是「重试」永远拿不到数据。本测试用**能按第 N 次订阅抛异常**的假仓储钉住这条契约。
 *
 * 计时全部走 `TestCoroutineScheduler`（与 `viewModelScope` 共享），
 * 因此 `WhileSubscribed(5s)` 的停止超时是被精确推进的，不靠 `Thread.sleep`。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    // ————————————————————————————————————————————————————————
    // 假仓储：按「第 N 次订阅」抛异常，并记录订阅/取消次数
    // ————————————————————————————————————————————————————————

    private class FlakyHomeRepository(
        private val delegate: MatchRepository,
        /** 前 N 次查询订阅失败（N=1 表示首次失败、第二次成功）。 */
        var failuresRemaining: Int
    ) : MatchRepository by delegate {

        /** 首页列表的数据源（`ALL` 先按它发射，再按 filter 过滤）。 */
        val summaries = MutableStateFlow<List<MatchSummary>>(emptyList())

        /** `observeSummaries` 被调用次数（= 新建了几条查询流）。 */
        var summaryRequests: Int = 0
            private set

        /** 查询流真正被收集的次数（= 底层订阅次数）。 */
        var summarySubscriptions: Int = 0
            private set

        /** 查询流被取消的次数（用于确认 WhileSubscribed 停止后确实退订）。 */
        var summaryCancellations: Int = 0
            private set

        /** 非空时，数据发射会挂起，便于观察中间的 Loading 态。 */
        var emitGate: CompletableDeferred<Unit>? = null

        /** rename 失败注入（验证操作错误仍然走 `errorMessage`）。 */
        var renameFailure: Throwable? = null

        override fun observeSummaries(filter: MatchStatusFilter): Flow<List<MatchSummary>> {
            summaryRequests += 1
            val failThisAttempt = failuresRemaining > 0
            if (failThisAttempt) failuresRemaining -= 1
            val allowedStatuses = filter.statuses()
            return flow {
                summarySubscriptions += 1
                try {
                    if (failThisAttempt) throw IllegalStateException(QUERY_ERROR_MESSAGE)
                    emitGate?.await()
                    emitAll(
                        summaries.map { list -> list.filter { it.status.name in allowedStatuses } }
                    )
                } finally {
                    summaryCancellations += 1
                }
            }
        }

        override suspend fun renameMatch(matchId: MatchId, title: String) {
            renameFailure?.let { throw it }
            delegate.renameMatch(matchId, title)
        }
    }

    private fun repository(failuresRemaining: Int = 0): FlakyHomeRepository =
        FlakyHomeRepository(
            delegate = FakeMatchRepository(config = AvalonScenario.eightPlayerConfig()),
            failuresRemaining = failuresRemaining
        )

    private fun summary(
        id: String,
        status: MatchStatus,
        title: String = id
    ): MatchSummary = MatchSummary(
        matchId = MatchId(id),
        title = title,
        playerCount = 8,
        status = status,
        rulesetId = "avalon.standard",
        updatedAtEpochMs = 0L,
        questsStarted = 1,
        mySeat = 1
    )

    private fun HomeUiState.titles(): List<String> = items.map { it.title }

    /** 收集 uiState 到列表（保留每一次发射，便于断言顺序）。 */
    private fun kotlinx.coroutines.CoroutineScope.collectStates(
        viewModel: HomeViewModel
    ): Pair<MutableList<HomeUiState>, kotlinx.coroutines.Job> {
        val states = mutableListOf<HomeUiState>()
        val job = launch(UnconfinedTestDispatcher(scheduler)) {
            viewModel.uiState.collect { states += it }
        }
        return states to job
    }

    private companion object {
        const val QUERY_ERROR_MESSAGE = "数据库读取失败（模拟）"
        const val STOP_TIMEOUT_MS = 5_100L
    }

    // ————————————————————————————————————————————————————————
    // T-C01：首次失败 → Error；retry() 后 → Data
    // ————————————————————————————————————————————————————————

    @Test
    fun `T-C01 首次订阅失败进入错误态 重试后恢复数据`() = runTest(UnconfinedTestDispatcher(scheduler)) {
        val repository = repository(failuresRemaining = 1)
        repository.summaries.value = listOf(summary("m1", MatchStatus.IN_PROGRESS, "对局一"))
        val viewModel = HomeViewModel(repository)

        val (states, job) = collectStates(viewModel)
        advanceUntilIdle()

        val failed = viewModel.uiState.value
        assertFalse("失败后不得停留在 Loading", failed.isLoading)
        assertEquals(
            "错误文案应为底层异常消息，实际=${failed.errorMessage}",
            QUERY_ERROR_MESSAGE,
            failed.errorMessage
        )
        assertTrue("错误态不得残留旧列表", failed.items.isEmpty())

        // 关键缺陷点：重试必须真正重新订阅上游，而不是只在旧错误上自增计数
        viewModel.retry()
        advanceUntilIdle()

        val recovered = viewModel.uiState.value
        assertNull("重试成功后错误必须清空，实际=${recovered.errorMessage}", recovered.errorMessage)
        assertFalse(recovered.isLoading)
        assertEquals("重试后应拿到数据", listOf("对局一"), recovered.titles())
        assertEquals("第一次失败 + 一次重试 = 两次底层订阅", 2, repository.summarySubscriptions)
        assertTrue(
            "收集序列里应出现过错误态与恢复后的数据态：" +
                states.map { it.errorMessage to it.titles() },
            states.any { it.errorMessage == QUERY_ERROR_MESSAGE } &&
                states.any { it.errorMessage == null && it.titles() == listOf("对局一") }
        )
        job.cancel()
    }

    // ————————————————————————————————————————————————————————
    // T-C02：连续失败两次，第三次成功
    // ————————————————————————————————————————————————————————

    @Test
    fun `T-C02 连续失败两次后第三次重试成功`() = runTest(UnconfinedTestDispatcher(scheduler)) {
        val repository = repository(failuresRemaining = 2)
        repository.summaries.value = listOf(summary("m1", MatchStatus.IN_PROGRESS, "对局一"))
        val viewModel = HomeViewModel(repository)

        val (_, job) = collectStates(viewModel)
        advanceUntilIdle()
        assertEquals(QUERY_ERROR_MESSAGE, viewModel.uiState.value.errorMessage)

        viewModel.retry()
        advanceUntilIdle()
        assertEquals(
            "第二次订阅同样失败，错误仍须可见",
            QUERY_ERROR_MESSAGE,
            viewModel.uiState.value.errorMessage
        )

        viewModel.retry()
        advanceUntilIdle()
        val recovered = viewModel.uiState.value
        assertNull("第三次订阅成功，错误必须清空，实际=${recovered.errorMessage}", recovered.errorMessage)
        assertEquals(listOf("对局一"), recovered.titles())
        assertEquals("三次订阅各建一条查询流", 3, repository.summaryRequests)
        assertEquals(3, repository.summarySubscriptions)
        job.cancel()
    }

    // ————————————————————————————————————————————————————————
    // T-C03：切换筛选不闪回旧筛选结果
    // ————————————————————————————————————————————————————————

    @Test
    fun `T-C03 切换筛选不闪回旧筛选结果`() = runTest(UnconfinedTestDispatcher(scheduler)) {
        val repository = repository()
        repository.summaries.value = listOf(
            summary("m1", MatchStatus.IN_PROGRESS, "进行中"),
            summary("m2", MatchStatus.FINISHED_GOOD, "已结束")
        )
        val viewModel = HomeViewModel(repository)

        val (states, job) = collectStates(viewModel)
        advanceUntilIdle()
        assertEquals(
            "ALL 筛选应看到两局",
            listOf("进行中", "已结束"),
            viewModel.uiState.value.titles()
        )

        // 让下一次查询挂住，以便观察「切换后、数据回来前」的中间态
        val gate = CompletableDeferred<Unit>()
        repository.emitGate = gate

        viewModel.onFilterChange(HomeFilter.FINISHED)
        advanceUntilIdle()

        val duringLoad = viewModel.uiState.value
        assertEquals("筛选必须立即生效", HomeFilter.FINISHED, duringLoad.filter)
        assertTrue("新一次查询应先给出 Loading", duringLoad.isLoading)
        assertTrue(
            "切换筛选时不得闪回旧筛选（ALL）的结果，实际=${duringLoad.titles()}",
            duringLoad.titles().isEmpty()
        )

        gate.complete(Unit)
        advanceUntilIdle()

        val filtered = viewModel.uiState.value
        assertFalse(filtered.isLoading)
        assertEquals("FINISHED 只应看到已结束的对局", listOf("已结束"), filtered.titles())

        // 收集序列：切换之后不得再出现旧的 ALL 结果
        val switchIndex = states.indexOfFirst { it.filter == HomeFilter.FINISHED }
        assertTrue("必须观察到 FINISHED 状态", switchIndex >= 0)
        val afterSwitch = states.drop(switchIndex)
        assertTrue(
            "切换筛选后不允许出现 ALL 的两条结果，实际=${afterSwitch.map { it.titles() }}",
            afterSwitch.none { it.titles() == listOf("进行中", "已结束") }
        )
        assertTrue(
            "切换后出现的结果只能是 FINISHED 子集：${afterSwitch.map { it.titles() }}",
            afterSwitch.all { it.titles().isEmpty() || it.titles() == listOf("已结束") }
        )
        job.cancel()
    }

    // ————————————————————————————————————————————————————————
    // T-C04：两个收集者共享一次底层订阅
    // ————————————————————————————————————————————————————————

    @Test
    fun `T-C04 两个收集者不产生双订阅`() = runTest(UnconfinedTestDispatcher(scheduler)) {
        val repository = repository()
        repository.summaries.value = listOf(summary("m1", MatchStatus.IN_PROGRESS, "对局一"))
        val viewModel = HomeViewModel(repository)

        val (statesA, jobA) = collectStates(viewModel)
        val (statesB, jobB) = collectStates(viewModel)
        advanceUntilIdle()

        assertEquals(
            "stateIn 共享上游：两个收集者只能有一条查询流",
            1,
            repository.summaryRequests
        )
        assertEquals(1, repository.summarySubscriptions)
        assertEquals(listOf("对局一"), statesA.last().titles())
        assertEquals(listOf("对局一"), statesB.last().titles())

        // 只停一个收集者仍然共享，不重新订阅
        jobA.cancel()
        advanceUntilIdle()
        assertEquals(1, repository.summarySubscriptions)

        // 两个都停 → WhileSubscribed 超时后退订
        jobB.cancel()
        advanceTimeBy(STOP_TIMEOUT_MS)
        runCurrent()
        assertEquals(
            "两个收集者都取消后上游应被取消（WhileSubscribed 5s）",
            1,
            repository.summaryCancellations
        )

        // 再次收集 → 需要新的订阅，且能正常拿到数据（T-C06 的一半）
        val (statesC, jobC) = collectStates(viewModel)
        advanceUntilIdle()
        assertEquals(2, repository.summaryRequests)
        assertEquals(2, repository.summarySubscriptions)
        assertEquals(listOf("对局一"), statesC.last().titles())
        jobC.cancel()
    }

    // ————————————————————————————————————————————————————————
    // T-C05：重试时先 Loading 后 Data，错误消息准确
    // ————————————————————————————————————————————————————————

    @Test
    fun `T-C05 重试先进入 Loading 再拿到数据且错误文案准确`() = runTest(UnconfinedTestDispatcher(scheduler)) {
        val repository = repository(failuresRemaining = 1)
        repository.summaries.value = listOf(summary("m1", MatchStatus.IN_PROGRESS, "对局一"))
        val viewModel = HomeViewModel(repository)

        val (states, job) = collectStates(viewModel)
        advanceUntilIdle()
        assertEquals(QUERY_ERROR_MESSAGE, viewModel.uiState.value.errorMessage)

        // 挂住数据发射，确保能观察到 retry 之后的 Loading 中间态
        val gate = CompletableDeferred<Unit>()
        repository.emitGate = gate
        viewModel.retry()
        advanceUntilIdle()

        val loading = viewModel.uiState.value
        assertTrue("重试期间必须回到 Loading", loading.isLoading)
        assertNull("重试开始即清掉旧错误，实际=${loading.errorMessage}", loading.errorMessage)
        assertTrue("Loading 期间不得展示旧列表", loading.items.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()
        val data = viewModel.uiState.value
        assertFalse(data.isLoading)
        assertNull(data.errorMessage)
        assertEquals(listOf("对局一"), data.titles())

        // 收集序列必须体现「失败 → 重新加载 → 数据」的顺序（去掉相邻重复项后）
        val tags = states.map { state ->
            when {
                state.errorMessage != null -> "error"
                state.isLoading -> "loading"
                else -> "data"
            }
        }.fold(mutableListOf<String>()) { acc, tag ->
            if (acc.lastOrNull() != tag) acc += tag
            acc
        }
        val errorIndex = tags.indexOf("error")
        val lastLoadingIndex = tags.lastIndexOf("loading")
        val dataIndex = tags.indexOf("data")
        assertTrue("序列里必须出现错误态，实际=$tags", errorIndex >= 0)
        assertTrue("错误之后必须重新出现 Loading，实际=$tags", lastLoadingIndex > errorIndex)
        assertTrue("Loading 之后才允许出现数据，实际=$tags", dataIndex > lastLoadingIndex)
        job.cancel()
    }

    // ————————————————————————————————————————————————————————
    // T-C06：收集停止后重新收集能正常拿到数据
    // ————————————————————————————————————————————————————————

    @Test
    fun `T-C06 收集停止后重新收集仍能拿到数据`() = runTest(UnconfinedTestDispatcher(scheduler)) {
        val repository = repository()
        repository.summaries.value = listOf(summary("m1", MatchStatus.IN_PROGRESS, "对局一"))
        val viewModel = HomeViewModel(repository)

        val (first, jobFirst) = collectStates(viewModel)
        advanceUntilIdle()
        assertEquals(listOf("对局一"), first.last().titles())

        jobFirst.cancel()
        advanceTimeBy(STOP_TIMEOUT_MS)
        runCurrent()

        // 停止期间数据变化：重新收集应拿到**最新**列表，而不是停在旧值
        repository.summaries.value = listOf(
            summary("m1", MatchStatus.IN_PROGRESS, "对局一"),
            summary("m2", MatchStatus.FINISHED_GOOD, "对局二")
        )

        val (second, jobSecond) = collectStates(viewModel)
        advanceUntilIdle()
        assertEquals(
            "重新收集应拿到最新数据，实际=${second.last().titles()}",
            listOf("对局一", "对局二"),
            second.last().titles()
        )
        assertEquals("重新收集需要一次新的底层订阅", 2, repository.summarySubscriptions)
        assertNull(second.last().errorMessage)
        jobSecond.cancel()
    }

    // ————————————————————————————————————————————————————————
    // 操作错误（rename/delete）与查询错误共用出口，且不破坏数据态
    // ————————————————————————————————————————————————————————

    @Test
    fun `重命名失败显示操作错误 重试后清空并恢复`() = runTest(UnconfinedTestDispatcher(scheduler)) {
        val repository = repository()
        repository.summaries.value = listOf(summary("m1", MatchStatus.IN_PROGRESS, "对局一"))
        repository.renameFailure = IllegalStateException("重命名失败（模拟）")
        val viewModel = HomeViewModel(repository)

        val (_, job) = collectStates(viewModel)
        advanceUntilIdle()

        viewModel.rename("m1", "新标题")
        advanceUntilIdle()
        assertEquals("重命名失败（模拟）", viewModel.uiState.value.errorMessage)
        assertEquals(
            "操作错误不得清空已加载的列表",
            listOf("对局一"),
            viewModel.uiState.value.titles()
        )

        viewModel.retry()
        advanceUntilIdle()
        assertNull("重试应清掉操作错误", viewModel.uiState.value.errorMessage)
        assertEquals(listOf("对局一"), viewModel.uiState.value.titles())
        job.cancel()
    }
}
