package com.roundtrace.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.audit.MutableClock
import com.roundtrace.app.core.data.MatchBundle
import com.roundtrace.app.core.data.RoomMatchRepository
import com.roundtrace.app.core.database.EventCodec
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.model.EventFactory
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.model.RevealState
import com.roundtrace.app.core.model.RoleAssignment
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.RoleSource
import com.roundtrace.app.core.model.SeatId
import com.roundtrace.app.core.replay.ReplayEngine
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.util.SequentialIdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NX-02 / RT-NX02-SNAP：`observeBundle` 的**响应式一致性**证据（B01～B09）。
 *
 * ## 为什么必须用真实 Room + 真实事务
 *
 * 缺陷不在「有没有订阅到变更」，而在**一份快照是否来自同一个数据库版本**：
 * 旧实现用四路独立 Flow `combine`，四路各自异步重查，`matches` 读到新事务、
 * `match_events` 还停在旧版本时，`combine` 就会把两个版本「拼」成一份快照发出去。
 * 用内存 fake 断言「订阅到了 4 张表」可以全绿，却证明不了一致性——所以这里全部走真实数据库。
 *
 * ## 为什么不用 `Thread.sleep` 赌时序
 *
 * 所有「等待某一类快照出现」都通过 [BundleStream] 的**信号通道**完成：
 * 收集协程每收到一份快照就 `trySend` 一个信号，测试侧 `receive()` 挂在信号上，
 * 由 `withTimeout` 兜底。只有**负向断言**（B08/B09「不应再有发射」）才需要一个
 * 有界的安静窗口 [awaitQuiet]——那是「证明没有在途发射」，不是「赌它一定发生」。
 *
 * ## 与旧实现（四路 combine）对拍的**实测**结果
 *
 * 先写测试、在旧实现上跑一遍（3 例失败），再改实现（11 例全绿）。差异表是实测的，不是推测：
 *
 * | 用例 | 旧实现（四路 combine） | 新实现（单事务读四表） |
 * |---|---|---|
 * | B01 首发 | 通过 | 通过（`emitInitialState = true`，不靠写一次触发） |
 * | B02 参与者改名 | 通过 | 通过 |
 * | B03 揭晓自洽 | 通过（这一轮没命中混搭窗口） | 通过 |
 * | B04a 追加事件 | **失败**：`matches.status=DRAFT` 与「事件重放 = IN_PROGRESS」混搭 | 通过 |
 * | B04b 修订事件 | 通过 | 通过 |
 * | B04c 撤销事件 | 通过 | 通过 |
 * | B05 删除发 `null` | 通过 | 通过 |
 * | B06 并发不混搭 | **失败**：抓到 2 份混搭（status 撕裂 + `revealedAt` 与角色撕裂） | 通过 |
 * | B07 双订阅者 | **失败**：同 B04a 的 status 撕裂 | 通过 |
 * | B08 取消收集 | 通过 | 通过 |
 * | B09 重复写入不冗余发射 | 通过 | 通过 |
 *
 * 说明：B03 / B09 在旧实现上**也可能通过**——它们命中的是「混搭窗口恰好没落在采样点上」，
 * 所以它们是**回归护栏**而不是复现用例；真正的复现证据是 B04a / B06 / B07。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObserveBundleConsistencyTest {

    private lateinit var database: RoundTraceDatabase
    private lateinit var repository: RoomMatchRepository
    private val registry = DefaultGameRulesRegistry()
    private val clock = MutableClock(1_700_000_000_000L)
    private val ids = SequentialIdGenerator("observe")
    private val events = EventFactory(ids, clock)
    private val playerCount = 8

    /** 本用例创建的全部收集器，`@After` 统一取消，避免协程泄漏到下一个用例。 */
    private val streams = mutableListOf<BundleStream>()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RoundTraceDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = RoomMatchRepository(
            database,
            registry,
            registry.avalonReducer,
            EventCodec(),
            ids,
            clock
        )
    }

    @After
    fun tearDown() {
        streams.forEach { it.cancel() }
        database.close()
    }

    // ————————————————————————————————————————————————————————
    // 夹具
    // ————————————————————————————————————————————————————————

    private suspend fun createMatch(): MatchId = repository.createMatch(
        MatchCreationDraft(
            title = "响应式一致性测试",
            playerCount = playerCount,
            mySeat = 1,
            roleSlots = AvalonPreset.standardPreset(playerCount),
            participants = MatchCreationDraft.defaultParticipants(playerCount),
            initialLeaderSeat = 1,
            presetId = AvalonPreset.presetId(playerCount)
        )
    )

    private fun role(seat: SeatId = 1): RoleAssignment = RoleAssignment(
        seat = seat,
        roleId = RoleId.MERLIN,
        source = RoleSource.REVEALED_AFTER_END,
        revealState = RevealState.HIDDEN
    )

    /** 建一个与生产相同的 `observeBundle` 收集器并登记到 `streams`。 */
    private fun stream(matchId: MatchId): BundleStream =
        BundleStream(matchId).also { streams += it }

    // ————————————————————————————————————————————————————————
    // B01：初次订阅立刻拿到数据（依赖 emitInitialState）
    // ————————————————————————————————————————————————————————

    @Test
    fun b01_firstSubscriptionEmitsCurrentSnapshotWithoutAnyWrite() = runBlocking {
        val match = createMatch()
        val collected = stream(match)
        collected.start()

        // 不写库、不触发任何变更：首发必须直接来自「订阅时的库内现状」。
        val first = awaitBundle(collected, "首发快照") { true }
        assertNotNull("订阅后必须立刻拿到快照（而不是等第一次写入）", first)
        assertEquals(match, first!!.summary.matchId)
        assertEquals(playerCount, first.participants.size)
        assertEquals(playerCount, first.roleAssignments.size)
        assertTrue("新建对局不应有事件", first.events.isEmpty())
        assertNull("新建对局尚未揭晓", first.revealedAt)
        assertBundleInvariants(first, "B01 首发")
    }

    // ————————————————————————————————————————————————————————
    // B02：参与者改名 → 新发射包含更新后的名字
    // ————————————————————————————————————————————————————————

    @Test
    fun b02_participantRenamePropagatesToLaterEmissions() = runBlocking {
        val match = createMatch()
        val collected = stream(match)
        collected.start()
        awaitBundle(collected, "改名前的首发快照") { true }

        repository.updateParticipants(
            match,
            MatchCreationDraft.defaultParticipants(playerCount).map { participant ->
                if (participant.seat == 3) {
                    participant.copy(displayName = "三号位新名字")
                } else {
                    participant
                }
            }
        )

        val renamed = awaitBundle(collected, "改名后的快照") { bundle ->
            bundle?.participants?.firstOrNull { it.seat == 3 }?.displayName == "三号位新名字"
        }
        assertNotNull("参与者改名必须自动刷新快照", renamed)
        // 其余座位不受影响，且快照整体仍然自洽。
        assertEquals(playerCount, renamed!!.participants.size)
        assertBundleInvariants(renamed, "B02 改名后")
    }

    // ————————————————————————————————————————————————————————
    // B03：揭晓后 roleAssignments 与 revealedAt 在**同一份发射内**自洽
    // ————————————————————————————————————————————————————————

    @Test
    fun b03_revealEmitsRolesAndRevealedAtInTheSameSnapshot() = runBlocking {
        val match = createMatch()
        repository.appendEvent(match, events.questStarted(1, leaderSeat = 1))

        val collected = stream(match)
        collected.start()
        awaitBundle(collected, "揭晓前的快照") { true }

        val result = repository.revealMatch(match, listOf(role(1)))
        assertTrue("揭晓前置写入失败：${result.error}", result.isSuccess)

        val revealed = awaitBundle(collected, "揭晓后的快照") { it?.revealedAt != null }
        assertNotNull("揭晓必须自动刷新快照", revealed)
        // 核心断言：拿到 revealedAt 的那一份发射，角色也必须是全部 REVEALED ——
        // 两者分属 matches / role_assignments 两张表，混搭快照会在这里露出来。
        assertTrue(
            "揭晓快照里 roleAssignments 必须全部 REVEALED，实际=${revealed!!.roleAssignments.mapValues { it.value.revealState }}",
            revealed.roleAssignments.values.all { it.revealState == RevealState.REVEALED }
        )
        assertEquals(clock.nowMs, revealed.revealedAt)
        assertBundleInvariants(revealed, "B03 揭晓后")
    }

    // ————————————————————————————————————————————————————————
    // B04：追加 / 撤销 / 修订 都能被观察到
    // ————————————————————————————————————————————————————————

    @Test
    fun b04a_appendedEventAppearsInLaterEmission() = runBlocking {
        val match = createMatch()
        val collected = stream(match)
        collected.start()
        awaitBundle(collected, "追加前的快照") { true }

        val started = events.questStarted(1, leaderSeat = 1)
        assertTrue(repository.appendEvent(match, started).isSuccess)

        val after = awaitBundle(collected, "追加后的快照") { it?.events?.size == 1 }
        assertNotNull("追加事件必须自动刷新快照", after)
        assertEquals(started.eventId, after!!.events.single().eventId)
        assertEquals(1, after.summary.questsStarted)
        assertBundleInvariants(after, "B04a 追加后")
    }

    @Test
    fun b04b_revisedEventReplacesTargetInLaterEmission() = runBlocking {
        val match = createMatch()
        val original = events.questStarted(1, leaderSeat = 1)
        assertTrue(repository.appendEvent(match, original).isSuccess)

        val collected = stream(match)
        collected.start()
        awaitBundle(collected, "修订前的快照") { it?.events?.size == 1 }

        val replacement = events.questStarted(1, leaderSeat = 2)
        val revision = repository.reviseEvent(match, original.eventId, replacement)
        assertTrue("修订失败：${revision.error}", revision.isSuccess)

        val after = awaitBundle(collected, "修订后的快照") { bundle ->
            bundle?.events?.singleOrNull()?.eventId == replacement.eventId
        }
        assertNotNull("修订必须自动刷新快照", after)
        val event = after!!.events.single()
        assertTrue("修订后应只剩替代版本", event is QuestStarted)
        assertEquals("替代版本必须带新的队长座位", 2, (event as QuestStarted).leaderSeat)
        assertBundleInvariants(after, "B04b 修订后")
    }

    @Test
    fun b04c_retractedEventDisappearsFromLaterEmission() = runBlocking {
        val match = createMatch()
        val started = events.questStarted(1, leaderSeat = 1)
        assertTrue(repository.appendEvent(match, started).isSuccess)

        val collected = stream(match)
        collected.start()
        awaitBundle(collected, "撤销前的快照") { it?.events?.size == 1 }

        assertTrue(repository.retractEvent(match, started.eventId).isSuccess)

        val after = awaitBundle(collected, "撤销后的快照") { it?.events?.isEmpty() == true }
        assertNotNull("撤销必须自动刷新快照", after)
        assertEquals("撤销后 questsStarted 必须归零", 0, after!!.summary.questsStarted)
        assertBundleInvariants(after, "B04c 撤销后")
    }

    // ————————————————————————————————————————————————————————
    // B05：删除对局 → 发射 null
    // ————————————————————————————————————————————————————————

    @Test
    fun b05_deletedMatchEmitsNull() = runBlocking {
        val match = createMatch()
        repository.appendEvent(match, events.questStarted(1, leaderSeat = 1))

        val collected = stream(match)
        collected.start()
        val before = awaitBundle(collected, "删除前的快照") { true }
        assertNotNull("删除前必须能读到快照", before)

        repository.deleteMatch(match)

        val after = awaitBundle(collected, "删除后必须发射 null") { it == null }
        assertNull("对局删除后必须发射 null（而不是继续发旧快照）", after)
        // 一次性的读取也必须一致地返回 null。
        assertNull(repository.getBundle(match))
    }

    // ————————————————————————————————————————————————————————
    // B06：跨表不变量 —— 并发写入期间**每一份**发射都必须自洽
    // ————————————————————————————————————————————————————————

    /**
     * 本轮的**核心用例**。
     *
     * 并发执行 5 次 `appendEvent`（动 `match_events` + `matches`）与 5 次 `revealMatch`
     * （动 `role_assignments` + `matches`），对收集到的**每一份**发射逐一校验
     * [bundleInvariantViolation] 里的四条不变量——任何一份混搭快照都会被抓到。
     *
     * 旧实现下本用例实测**失败**（抓到 2 份混搭）：一次事务提交会让四路 Flow 各自重查，
     * `combine` 会在「角色已 REVEALED、`matches.revealedAt` 还是 null」这类中间态上发射。
     * `distinctUntilChanged()` 只能去重，救不了一致性。
     */
    @Test
    fun b06_concurrentWritesNeverExposeMixedSnapshot() = runBlocking {
        val match = createMatch()
        val collected = stream(match)
        val violations = mutableListOf<String>()

        collected.start { bundle ->
            val problem = bundleInvariantViolation(bundle)
            if (problem != null) {
                synchronized(violations) { violations += problem }
            }
        }
        awaitBundle(collected, "并发前的首发快照") { true }

        coroutineScope {
            List(5) { index ->
                listOf(
                    async(Dispatchers.IO) {
                        repository.appendEvent(match, events.questStarted(index + 1, leaderSeat = 1))
                    },
                    async(Dispatchers.IO) { repository.revealMatch(match, listOf(role(1))) }
                )
            }.flatten().awaitAll().forEach { result ->
                assertTrue("并发写入不允许失败：${result.error}", result.isSuccess)
            }
        }

        // 安静窗口之内若还有在途发射，说明不变量校验还没覆盖完，必须等它落地。
        val settled = awaitQuiet(collected)
        val problems = synchronized(violations) { violations.toList() }
        assertTrue(
            "并发写入期间发现混搭快照（共 ${problems.size} 份）：\n" + problems.joinToString("\n"),
            problems.isEmpty()
        )
        assertTrue("并发写入期间必须真的有多份发射，否则本用例没有证明力（实际 $settled 份）", settled >= 2)

        // 收尾快照必须包含全部 5 次追加，且与一次性读取逐字段相同。
        val last = collected.emissions.last()
        assertNotNull(last)
        assertEquals(5, last!!.events.count { it is QuestStarted })
        assertTrue("收尾快照已揭晓", last.revealedAt != null)
        assertBundleInvariants(last, "B06 收尾")
        assertEquals("安静后的最后一份发射必须等于一次性读取的结果", repository.getBundle(match), last)
    }

    // ————————————————————————————————————————————————————————
    // B07：两个订阅者互不影响，各自都拿到一致快照
    // ————————————————————————————————————————————————————————

    @Test
    fun b07_twoSubscribersEachSeeConsistentSnapshots() = runBlocking {
        val match = createMatch()
        val first = stream(match)
        val second = stream(match)
        first.start()
        second.start()

        val initialA = awaitBundle(first, "订阅者 A 首发") { true }
        val initialB = awaitBundle(second, "订阅者 B 首发") { true }
        assertEquals("两个订阅者的首发必须一致", initialA, initialB)

        assertTrue(repository.appendEvent(match, events.questStarted(1, leaderSeat = 1)).isSuccess)
        val afterA = awaitBundle(first, "订阅者 A 追加后") { it?.events?.size == 1 }
        val afterB = awaitBundle(second, "订阅者 B 追加后") { it?.events?.size == 1 }
        assertEquals("两个订阅者必须看到同一份快照", afterA, afterB)
        assertBundleInvariants(afterA!!, "B07 订阅者 A")
        assertBundleInvariants(afterB!!, "B07 订阅者 B")

        // 取消 A 不应影响 B：B 必须继续收到后续变更。
        first.cancelAndJoin()
        assertTrue(repository.appendEvent(match, events.questStarted(2, leaderSeat = 2)).isSuccess)
        val afterCancelB = awaitBundle(second, "取消 A 之后订阅者 B 的快照") {
            it?.events?.size == 2
        }
        assertNotNull("取消一个订阅者不得影响另一个订阅者", afterCancelB)
        assertEquals(2, afterCancelB!!.summary.questsStarted)
        assertBundleInvariants(afterCancelB, "B07 取消 A 后 B")
    }

    // ————————————————————————————————————————————————————————
    // B08：取消收集后不再发射
    // ————————————————————————————————————————————————————————

    @Test
    fun b08_cancelledCollectorReceivesNothingAfterwards() = runBlocking {
        val match = createMatch()
        val collected = stream(match)
        collected.start()
        awaitBundle(collected, "取消前的首发快照") { true }

        collected.cancelAndJoin()
        val beforeWrite = collected.count

        // 取消之后再写真实数据：已取消的收集器不得再收到任何东西。
        assertTrue(repository.appendEvent(match, events.questStarted(1, leaderSeat = 1)).isSuccess)
        val leaked = withTimeoutOrNull(QUIET_WINDOW_MS) { collected.awaitNextSignal() }
        assertNull("收集已取消，不应再收到发射", leaked)
        assertEquals("取消后发射条数不得增长", beforeWrite, collected.count)

        // 取消不能污染共享状态：新建订阅者必须立刻拿到包含刚才那次写入的快照。
        val fresh = stream(match)
        fresh.start()
        val latest = awaitBundle(fresh, "新订阅者的快照") { it?.events?.size == 1 }
        assertNotNull("取消旧收集后，新订阅必须仍然可用", latest)
        assertBundleInvariants(latest!!, "B08 新订阅")
    }

    // ————————————————————————————————————————————————————————
    // B09：同一内容重复写入不产生多余发射（distinctUntilChanged）
    // ————————————————————————————————————————————————————————

    @Test
    fun b09_repeatedIdenticalWriteDoesNotProduceExtraEmission() = runBlocking {
        val match = createMatch()
        val collected = stream(match)
        collected.start()
        awaitBundle(collected, "首发快照") { true }

        // 时钟冻结：两次相同写入得到**逐字段相同**的快照（updatedAt 也不会变）。
        assertTrue(repository.revealMatch(match, listOf(role(1))).isSuccess)
        assertNotNull(awaitBundle(collected, "首次揭晓的快照") { it?.revealedAt != null })
        val baseline = awaitQuiet(collected)

        assertTrue(repository.revealMatch(match, listOf(role(1))).isSuccess)
        val after = awaitQuiet(collected)

        // 宽松上界：允许 1 份（写入确实发生了，只是内容相同），但绝不允许「每表一份」的重放。
        val extra = after - baseline
        assertTrue(
            "重复相同内容不应产生多余发射：baseline=$baseline, after=$after, extra=$extra",
            extra <= 1
        )
    }

    // ————————————————————————————————————————————————————————
    // 不变量
    // ————————————————————————————————————————————————————————

    /**
     * 一份 [MatchBundle] 必须来自**同一个数据库版本**，因此以下四条必须同时成立：
     *
     * 1. `summary.questsStarted` == 有效 `QuestStarted` 事件条数；
     * 2. `participants.size` == `summary.playerCount`；
     * 3. `roleAssignments` 全部 REVEALED ⟺ `revealedAt != null`
     *    （分别来自 `role_assignments` 与 `matches`，是本轮缺陷最容易露头的地方）；
     * 4. `summary.status` == 用同一份 `events` + `roleAssignments` 重放出的状态
     *    （`matches.status` 与 `match_events` 的跨表一致性）。
     */
    private fun assertBundleInvariants(bundle: MatchBundle, label: String) {
        val problem = bundleInvariantViolation(bundle)
        if (problem != null) throw AssertionError("$label：$problem")
    }

    /** 与 [assertBundleInvariants] 同一套规则，供并发用例逐份收集违规原因。 */
    private fun bundleInvariantViolation(bundle: MatchBundle?): String? {
        if (bundle == null) return null // null 由各用例自己断言语义（B05 才是期望 null）
        val questStarts = bundle.events.count { it is QuestStarted }
        if (questStarts != bundle.summary.questsStarted) {
            return "summary.questsStarted=${bundle.summary.questsStarted} 但事件里只有 $questStarts 个 QUEST_STARTED"
        }
        if (bundle.participants.size != bundle.summary.playerCount) {
            return "participants.size=${bundle.participants.size} 与 summary.playerCount=${bundle.summary.playerCount} 不一致"
        }
        val allRevealed = bundle.roleAssignments.isNotEmpty() &&
            bundle.roleAssignments.values.all { it.revealState == RevealState.REVEALED }
        if (allRevealed != (bundle.revealedAt != null)) {
            val states = bundle.roleAssignments.mapValues { it.value.revealState }
            return "角色揭晓状态与 revealedAt 不一致：revealedAt=${bundle.revealedAt}, roles=$states"
        }
        val replayed = try {
            ReplayEngine(registry.avalonReducer).replay(bundle.toReconstructedMatch()).state.status
        } catch (failure: RuntimeException) {
            return "快照无法重放（数据本身不完整）：${failure.message}"
        }
        if (replayed != bundle.summary.status) {
            return "matches.status=${bundle.summary.status} 与事件重放结果=$replayed 不一致" +
                "（events=${bundle.events.size}）"
        }
        return null
    }

    // ————————————————————————————————————————————————————————
    // 收集器
    // ————————————————————————————————————————————————————————

    /**
     * `observeBundle` 的后台收集器。
     *
     * 设计要点：
     * - 快照列表与信号通道**不与收集协程共命运**：取消收集后仍可读历史条数、仍可用通道做负向断言；
     * - 记录与发信号在同一段逻辑内完成（先记录、后 `trySend`），等待方先查条数再等信号，
     *   因此不会漏掉已经到达的快照；
     * - 收集跑在 `Dispatchers.Default`：`observeBundle` 内部会开真实事务，
     *   不能用测试调度器（那会把真实 IO 挂住）。
     */
    private inner class BundleStream(private val matchId: MatchId) {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val snapshots = mutableListOf<MatchBundle?>()
        private val signals = Channel<Unit>(Channel.UNLIMITED)
        private var job: Job? = null

        /** 已收集到的快照（快照副本，调用方随便读）。 */
        val emissions: List<MatchBundle?>
            get() = synchronized(snapshots) { snapshots.toList() }

        /** 已收集到的快照条数。 */
        val count: Int
            get() = synchronized(snapshots) { snapshots.size }

        /** 开始收集；[onSnapshot] 在**每一份**快照到达时被调用（用于逐份校验不变量）。 */
        fun start(onSnapshot: ((MatchBundle?) -> Unit)? = null) {
            if (job != null) return
            job = scope.launch {
                repository.observeBundle(matchId).collect { bundle ->
                    synchronized(snapshots) { snapshots += bundle }
                    onSnapshot?.invoke(bundle)
                    signals.trySend(Unit)
                }
            }
        }

        /** 挂起直到下一份快照的信号（或超时）。 */
        suspend fun awaitNextSignal() {
            signals.receive()
        }

        /** 取消收集并**等它真正结束**：之后不会再有快照写进 [snapshots]。 */
        suspend fun cancelAndJoin() {
            job?.cancelAndJoin()
            job = null
            scope.cancel()
        }

        /** 同步收尾用（`@After`）：不等，只保证协程被取消。 */
        fun cancel() {
            scope.cancel()
        }
    }

    // ————————————————————————————————————————————————————————
    // 等待工具
    // ————————————————————————————————————————————————————————

    /**
     * 等到出现满足 [predicate] 的快照（含 `null` 语义，由 predicate 自己判断）。
     *
     * 逐份扫描已收集的快照，扫描完仍没有就挂起等下一个信号——因此**额外的中间发射
     * 不会让断言错位**，也不会依赖「第 N 份一定是哪一份」。
     */
    private suspend fun awaitBundle(
        stream: BundleStream,
        label: String,
        timeoutMs: Long = AWAIT_TIMEOUT_MS,
        predicate: (MatchBundle?) -> Boolean
    ): MatchBundle? {
        var cursor = 0
        var found = false
        var result: MatchBundle? = null
        try {
            withTimeout(timeoutMs) {
                while (!found) {
                    val snapshot = stream.emissions
                    while (cursor < snapshot.size && !found) {
                        val candidate = snapshot[cursor]
                        cursor += 1
                        if (predicate(candidate)) {
                            found = true
                            result = candidate
                        }
                    }
                    if (!found) stream.awaitNextSignal()
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(
                "等待超时（${timeoutMs}ms）：$label；已收到 ${stream.count} 份快照",
                timeout
            )
        }
        return result
    }

    /**
     * 等到收集器「安静」：连续 [QUIET_WINDOW_MS] 没有任何新发射，返回当时的快照条数。
     *
     * 这是**负向断言**的等待手法——不是 sleep 之后赌某件事发生过，而是用「这么久都没有信号」
     * 来证明没有在途发射。
     */
    private suspend fun awaitQuiet(
        stream: BundleStream,
        windowMs: Long = QUIET_WINDOW_MS
    ): Int {
        while (true) {
            val signalled = withTimeoutOrNull(windowMs) { stream.awaitNextSignal() }
            if (signalled == null) return stream.count
        }
    }

    private companion object {
        /** 等待某一类快照出现的上限。真库事务都在毫秒级，5s 只是防死锁兜底。 */
        const val AWAIT_TIMEOUT_MS = 5_000L

        /** 负向断言的安静窗口：这段时间没有信号就认定没有在途发射。 */
        const val QUIET_WINDOW_MS = 400L
    }
}
