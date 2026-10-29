package io.github.djp440.creepercapital.client;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.github.djp440.creepercapital.core.kline.Candle;
import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.github.djp440.creepercapital.core.kline.Klines;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.network.KlineHistoryPayload;
import io.github.djp440.creepercapital.network.KlineRequestPayload;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 客户端缓存的当前合约、当前周期的 K 线：打开界面、切换合约或周期时向服务端拉一次历史，之后每个行情步用快照增量更新当前这一根。
 * <p>
 * 增量更新与服务端聚合用的是同一份核心逻辑（{@link Klines#advance}），所以结果完全一致。
 * 行情步不连续（开发命令快进、丢包）时增量更新会漏数据，这里发现后重新拉取。
 * <p>
 * 网络处理器和界面渲染都在客户端主线程，不需要加锁。
 */
public final class ClientKlines {
    /** 客户端最多留多少根：服务端一次给 500 根，界面开着不动也会一直往后追加，超过就从最旧的丢。 */
    private static final int MAX_CANDLES = 1_000;

    private static CandlePeriod selected = CandlePeriod.M1;
    /** 当前周期已到达的 K 线（从旧到新，最后一根是正在走的）；null 表示还没收到。 */
    private static @Nullable List<Candle> candles;
    /** 最近一次并入 K 线的行情步，用来发现不连续。 */
    private static long lastStep = -1;

    private ClientKlines() {
    }

    public static CandlePeriod selected() {
        return selected;
    }

    /** 已到达的 K 线；还在加载时为空。 */
    static @Nullable List<Candle> candles() {
        return candles;
    }

    /** 打开界面时调用：保留已有的 K 线继续显示，同时向服务端要一份最新的。 */
    static void open() {
        request();
    }

    /** 切换了合约（含第一个合约到达）：旧合约的 K 线立刻丢弃，界面显示加载中，直到新合约的历史到达。 */
    static void onContractSelected() {
        candles = null;
        lastStep = -1;
        request();
    }

    /** 切换周期：旧周期的 K 线立刻丢弃，界面显示加载中，直到新周期的历史到达。 */
    public static void select(CandlePeriod period) {
        if (period == selected && candles != null) {
            return;
        }
        selected = period;
        candles = null;
        request();
    }

    static void accept(KlineHistoryPayload payload) {
        // 切换过合约或周期后，上一个合约、上一个周期的迟到响应直接丢弃
        if (payload.period() != selected || !ClientMarket.isSelected(payload.symbol())) {
            return;
        }
        candles = new ArrayList<>(payload.candles());
        // 同一连接上包是按序到达的：此前收到的快照都已经包含在这份历史里了
        lastStep = ClientMarket.latest().map(MarketSnapshot::step).orElse(-1L);
    }

    static void onSnapshot(MarketSnapshot snapshot) {
        if (candles == null) {
            return;
        }
        if (snapshot.step() != lastStep + 1) {
            request();
        }
        // 缺的那几步由重新拉取的历史补上；这里照常并入，历史到达后整体替换
        Klines.advance(candles, selected, snapshot.time().totalSeconds(), snapshot.lastPrice(), snapshot.volume());
        lastStep = snapshot.step();
        if (candles.size() > MAX_CANDLES) {
            candles.subList(0, candles.size() - MAX_CANDLES).clear();
        }
    }

    static void clear() {
        candles = null;
        lastStep = -1;
    }

    private static void request() {
        ClientMarket.selectedSymbol().ifPresent(symbol ->
                ClientPacketDistributor.sendToServer(new KlineRequestPayload(symbol, selected)));
    }
}
