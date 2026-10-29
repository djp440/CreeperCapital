package io.github.djp440.creepercapital.core.market;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.djp440.creepercapital.core.kline.Klines;
import io.github.djp440.creepercapital.core.kline.Stats24h;

/**
 * 全服共享的市场，只由服务器 tick 推进（ADR-0003）。市场里有若干个合约，共用同一个时钟和暂停状态，
 * 但每个合约有自己的行情模型和随机数流，互不影响。
 */
public final class Market {
    /** 每个行情步包含的服务器 tick 数。 */
    public static final int TICKS_PER_STEP = 20;
    /** 每个行情步跨越的市场秒数：1 真实秒对应 6 市场秒（ADR-0003）。 */
    static final int MARKET_SECONDS_PER_STEP = 6;

    private final long seed;
    /** 当前生效的合约，按配置顺序排列。 */
    private final Map<String, ContractMarket> contracts = new LinkedHashMap<>();
    /** 存档里有、配置里暂时没有的合约的状态：不推进，原样保存，重新加回配置后接着走。 */
    private final List<ContractState> dormant;
    private final List<JumpListener> jumpListeners = new ArrayList<>();
    private int ticksIntoStep;
    private long step;
    private boolean paused;

    /**
     * 新建市场。
     *
     * @param seed  行情随机数种子，同一种子和同一份合约定义走出同一条行情
     * @param specs 合约定义，至少一个，交易代码不能重复
     */
    public Market(long seed, List<ContractSpec> specs) {
        this.seed = seed;
        this.dormant = List.of();
        for (ContractSpec spec : checked(specs)) {
            contracts.put(spec.symbol(), new ContractMarket(seed, spec));
        }
    }

    private Market(MarketState state, List<ContractSpec> specs) {
        this.seed = state.seed();
        this.ticksIntoStep = state.ticksIntoStep();
        this.step = state.step();
        this.paused = state.paused();
        Map<String, ContractState> saved = new LinkedHashMap<>();
        for (ContractState contract : state.contracts()) {
            saved.put(contract.symbol(), contract);
        }
        for (ContractSpec spec : checked(specs)) {
            ContractState contract = saved.remove(spec.symbol());
            contracts.put(spec.symbol(),
                    contract == null ? new ContractMarket(seed, spec) : new ContractMarket(spec, contract));
        }
        this.dormant = List.copyOf(saved.values());
    }

    private static List<ContractSpec> checked(List<ContractSpec> specs) {
        if (specs.isEmpty()) {
            throw new IllegalArgumentException("市场至少要有一个合约");
        }
        Set<String> symbols = new HashSet<>();
        for (ContractSpec spec : specs) {
            if (!symbols.add(spec.symbol())) {
                throw new IllegalArgumentException("合约的交易代码重复：" + spec.symbol());
            }
        }
        return specs;
    }

    /**
     * 从保存的状态恢复市场，接着停下的地方走，不补算停机期间的行情。
     * 合约以 {@code specs}（当前配置）为准：配置里新加的合约从起始价格开始，配置里去掉的合约状态原样留在存档里。
     */
    public static Market restore(MarketState state, List<ContractSpec> specs) {
        return new Market(state, specs);
    }

    /**
     * 需要持久化的全部状态，包括是否暂停和每个合约正在走的 1m K 线。已收盘的 K 线不在其中，
     * 由宿主用 {@link io.github.djp440.creepercapital.core.kline.KlineStore#bind} 接到世界目录下的文件。
     */
    public MarketState state() {
        List<ContractState> states = new ArrayList<>();
        for (ContractMarket contract : contracts.values()) {
            states.add(contract.state());
        }
        states.addAll(dormant);
        return new MarketState(seed, step, ticksIntoStep, paused, states);
    }

    /**
     * 某个合约某个行情步的模拟成交（只展示用，见 {@link SimulatedTrades}）；无状态，不改变行情，可对同一步重复调用。
     *
     * @param previousLastPrice 上一个行情步的最新价格，未知时传 NaN
     */
    public List<MarketTrade> simulatedTrades(MarketSnapshot snapshot, double previousLastPrice) {
        return SimulatedTrades.of(seed, spec(snapshot.symbol()), snapshot, previousLastPrice);
    }

    /** 订阅跳跃事件（随机的和手动的），新闻文字从这里挂接。 */
    public void addJumpListener(JumpListener listener) {
        jumpListeners.add(listener);
    }

    /**
     * 推进一个服务器 tick。暂停时什么都不发生：行情步、价格、市场时间和 tick 计数全部冻结。
     *
     * @return 这个 tick 恰好完成一个行情步时，返回该步每个合约的快照（按配置顺序）；否则为空
     */
    public List<MarketSnapshot> tick() {
        if (paused) {
            return List.of();
        }
        ticksIntoStep++;
        if (ticksIntoStep < TICKS_PER_STEP) {
            return List.of();
        }
        ticksIntoStep = 0;
        step++;
        List<MarketSnapshot> snapshots = new ArrayList<>(contracts.size());
        for (ContractMarket contract : contracts.values()) {
            snapshots.add(contract.next(step, this::fireJump));
        }
        return snapshots;
    }

    private void fireJump(JumpEvent event) {
        for (JumpListener listener : jumpListeners) {
            listener.jumped(event);
        }
    }

    /**
     * 不等服务器 tick，直接推进若干个行情步，结果与逐 tick 推进完全相同（内部仍按 20 个 tick 凑一个行情步）。
     * 仅供开发命令快进行情用，正常运行时市场只由服务器 tick 推进（ADR-0003）。暂停时不前进。
     *
     * @return 最后一步每个合约的快照；一步也没走时为空
     */
    public List<MarketSnapshot> advanceSteps(int steps) {
        List<MarketSnapshot> last = List.of();
        int done = 0;
        while (done < steps && !paused) {
            List<MarketSnapshot> snapshots = tick();
            if (!snapshots.isEmpty()) {
                last = snapshots;
                done++;
            }
        }
        return last;
    }

    /**
     * 预生成历史（Backfill）：新建世界时一次性生成过去若干个市场日的行情，让 K 线一开始就有完整历史。
     * <p>
     * 走的就是正常的行情步（同一个模型、同一条随机数流），所以预生成结束时的状态就是开市状态，
     * 与之后的实时行情天然衔接，没有断层。历史里的跳跃不通知监听者（过去的事不发新闻）。
     * <p>
     * 停在最后一个市场日的最后一个行情步之前，这样 1d K 线恰好是 {@code days} 根（最后一根是尚未走完的当日 K 线）。
     * 要在绑定 K 线存储之后调用，历史才会随 K 线收盘追加进文件。
     *
     * @param days 预生成多少个市场日，至少 1
     * @throws IllegalStateException 市场已经走过行情步（已有世界重启时不能重复预生成）
     */
    public void backfill(int days) {
        if (days < 1) {
            throw new IllegalArgumentException("预生成至少 1 个市场日：" + days);
        }
        if (step != 0 || ticksIntoStep != 0) {
            throw new IllegalStateException("只有还没走过行情步的新市场才能预生成历史，当前行情步 " + step);
        }
        long steps = (long) days * IndexModel.STEPS_PER_DAY - 1;
        while (step < steps) {
            step++;
            for (ContractMarket contract : contracts.values()) {
                contract.next(step, event -> {
                });
            }
        }
    }

    /**
     * 让某个合约在下一个行情步跳跃（{@code /creeper dev market jump}）。跳跃与那一步的日常波动一起生效，
     * 并作为 {@link JumpEvent.Cause#MANUAL} 事件报告；排队的跳跃随市场状态保存。
     *
     * @param changeRatio 价格变化比例，+0.1 涨 10%，-0.2 跌 20%；必须大于 -1
     * @throws IllegalArgumentException 没有这个合约，或幅度不合法
     */
    public void queueJump(String symbol, double changeRatio) {
        if (!(changeRatio > -1) || !Double.isFinite(changeRatio)) {
            throw new IllegalArgumentException("跳跃幅度必须是大于 -100% 的有限数：" + changeRatio);
        }
        contract(symbol).queueJump(changeRatio);
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    public boolean isPaused() {
        return paused;
    }

    /** 当前生效的合约的交易代码，按配置顺序。 */
    public List<String> symbols() {
        return List.copyOf(contracts.keySet());
    }

    public ContractSpec spec(String symbol) {
        return contract(symbol).spec();
    }

    public MarketSnapshot snapshot(String symbol) {
        return contract(symbol).snapshot(step);
    }

    /** 每个合约此刻的快照，按配置顺序。 */
    public List<MarketSnapshot> snapshots() {
        List<MarketSnapshot> snapshots = new ArrayList<>(contracts.size());
        for (ContractMarket contract : contracts.values()) {
            snapshots.add(contract.snapshot(step));
        }
        return snapshots;
    }

    /** 某个合约的 K 线（以最新价格计）。 */
    public Klines klines(String symbol) {
        return contract(symbol).klines();
    }

    /** 某个合约行情栏的 24h 统计，与 K 线数据一致；还没有行情步时为空。 */
    public Optional<Stats24h> stats24h(String symbol) {
        return contract(symbol).stats24h();
    }

    /** 某个合约此刻的市场状态；供同包的统计测试核对溢价与市场状态的关系。 */
    Regime regime(String symbol) {
        return contract(symbol).regime();
    }

    private ContractMarket contract(String symbol) {
        ContractMarket contract = contracts.get(symbol);
        if (contract == null) {
            throw new IllegalArgumentException("没有这个合约：" + symbol);
        }
        return contract;
    }
}
