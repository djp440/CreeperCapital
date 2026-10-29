package io.github.djp440.creepercapital.server;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarginTiers;
import io.github.djp440.creepercapital.core.market.VolatilityProfile;
import net.neoforged.fml.loading.FMLPaths;

/**
 * 合约的服务端配置文件 {@code config/creepercapital-contracts.json}：每个合约的标的资源、起始价格、基础成交量、波动率倾向和交易参数。
 * <p>
 * 点差（{@code spread}，CRP，默认起始价格的 0.05%）、最高杠杆（{@code max_leverage}，默认 20）、数量精度
 * （{@code quantity_decimals}，默认 3 位）、流动性深度（{@code depth_slippage}，名义价值 10,000 CRP 的市价单的平均滑点，
 * 默认 0.001 即 0.1%，0 表示无限深）和维持保证金分档（{@code maintenance_tiers}，默认按最高杠杆推出五档）五项可以不写，
 * 写了就按写的算；已存在的配置文件缺这些字段时不会被改写。
 * 另有持仓总量上限比例（{@code open_interest_ratio}，默认 0.5）：该合约全体玩家未平仓名义价值之和不得超过流动性池权益乘以它（#17）。
 * 分档是数组，每档 {@code up_to}（名义价值上界，CRP）、{@code max_leverage}、{@code maintenance_rate}；
 * 上界递增、杠杆不升、费率不降，第一档的杠杆必须等于 {@code max_leverage}，最后一档的上界就是单个仓位的名义价值上限。
 * <p>
 * 文件不存在时按内置的两个合约（DIAMONDCRP、GUNPOWDERCRP）自动生成；改了文件，重启服务器后生效：
 * 市场的状态随存档保存，只有行情参数以文件为准，所以改波动率倾向不会让价格跳变。
 * 文件有错（JSON 不合法、字段不合法、波动率倾向兑现不了、交易代码重复）时整体回退到内置的两个合约，
 * 把原因写进日志，并且<b>不覆盖</b>文件，方便修改后重启。
 */
public final class ContractConfig {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String FILE_NAME = "creepercapital-contracts.json";

    private static final Codec<VolatilityProfile> VOLATILITY_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.fieldOf("daily_volatility").forGetter(VolatilityProfile::dailyVolatility),
            Codec.DOUBLE.fieldOf("jumps_per_day").forGetter(VolatilityProfile::jumpsPerDay),
            Codec.DOUBLE.fieldOf("jump_min").forGetter(VolatilityProfile::jumpMin),
            Codec.DOUBLE.fieldOf("jump_max").forGetter(VolatilityProfile::jumpMax),
            Codec.DOUBLE.fieldOf("premium_bias").forGetter(VolatilityProfile::premiumBias))
            .apply(i, VolatilityProfile::new));

    /** 金额在配置文件里写成数字（CRP），转成定点数时按十进制字面值，不引入二进制浮点误差。 */
    private static final Codec<Money> CRP_CODEC = Codec.DOUBLE.xmap(
            value -> Money.parse(BigDecimal.valueOf(value).toPlainString()),
            money -> new BigDecimal(money.units()).movePointLeft(Money.SCALE).doubleValue());

    private static final Codec<MarginTiers.Definition> TIER_CODEC = RecordCodecBuilder.create(i -> i.group(
            CRP_CODEC.fieldOf("up_to").forGetter(MarginTiers.Definition::upTo),
            Codec.INT.fieldOf("max_leverage").forGetter(MarginTiers.Definition::maxLeverage),
            Codec.DOUBLE.fieldOf("maintenance_rate").forGetter(MarginTiers.Definition::maintenanceRate))
            .apply(i, MarginTiers.Definition::new));

    private static final Codec<ContractSpec> CONTRACT_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("symbol").forGetter(ContractSpec::symbol),
            Codec.STRING.fieldOf("underlying").forGetter(ContractSpec::underlying),
            Codec.DOUBLE.fieldOf("initial_price").forGetter(ContractSpec::initialPrice),
            Codec.DOUBLE.fieldOf("base_volume").forGetter(ContractSpec::baseVolume),
            VOLATILITY_CODEC.fieldOf("volatility").forGetter(ContractSpec::volatility),
            Codec.DOUBLE.optionalFieldOf("spread").forGetter(spec -> Optional.of(spec.spread())),
            Codec.INT.optionalFieldOf("max_leverage").forGetter(spec -> Optional.of(spec.maxLeverage())),
            Codec.INT.optionalFieldOf("quantity_decimals").forGetter(spec -> Optional.of(spec.quantityDecimals())),
            Codec.DOUBLE.optionalFieldOf("depth_slippage").forGetter(spec -> Optional.of(spec.depthSlippage())),
            TIER_CODEC.listOf().optionalFieldOf("maintenance_tiers")
                    .forGetter(spec -> Optional.of(spec.marginTiers().definitions())),
            Codec.DOUBLE.optionalFieldOf("open_interest_ratio").forGetter(spec -> Optional.of(spec.openInterestRatio())))
            .apply(i, (symbol, underlying, initialPrice, baseVolume, volatility, spread, maxLeverage, quantityDecimals,
                    depthSlippage, tiers, openInterestRatio) ->
                    new ContractSpec(symbol, underlying, initialPrice, baseVolume, volatility,
                            spread.orElseGet(() -> ContractSpec.defaultSpread(initialPrice)),
                            maxLeverage.orElse(ContractSpec.DEFAULT_MAX_LEVERAGE),
                            quantityDecimals.orElse(ContractSpec.DEFAULT_QUANTITY_DECIMALS),
                            depthSlippage.orElse(ContractSpec.DEFAULT_DEPTH_SLIPPAGE),
                            tiers.map(MarginTiers::of).orElse(null),
                            openInterestRatio.orElse(ContractSpec.DEFAULT_OPEN_INTEREST_RATIO))));

    private ContractConfig() {
    }

    /** 服务器配置目录下的合约配置文件。 */
    public static Path defaultPath() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    /**
     * 读取合约配置。
     *
     * @return 配置里的合约，按文件顺序；文件缺失或有错时是内置的两个合约
     */
    public static List<ContractSpec> load(Path file) {
        if (!Files.exists(file)) {
            writeDefaults(file);
            return ContractSpec.defaults();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<ContractSpec> specs = parse(JsonParser.parseReader(reader));
            LOGGER.info("已读取合约配置 {}：{}", file, specs.stream().map(ContractSpec::symbol).toList());
            return specs;
        } catch (IOException | JsonParseException | IllegalArgumentException e) {
            LOGGER.error("合约配置 {} 无效，本次运行使用内置的 DIAMONDCRP 与 GUNPOWDERCRP（文件保持原样，修改后重启即可）：{}", file,
                    e.getMessage());
            return ContractSpec.defaults();
        }
    }

    /** 把合约配置序列化成配置文件的 JSON 内容（带缩进）。 */
    public static String toJson(List<ContractSpec> specs) {
        JsonArray contracts = new JsonArray();
        for (ContractSpec spec : specs) {
            contracts.add(CONTRACT_CODEC.encodeStart(JsonOps.INSTANCE, spec).getOrThrow());
        }
        JsonObject root = new JsonObject();
        root.add("contracts", contracts);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + System.lineSeparator();
    }

    /**
     * 解析配置文件的 JSON。
     *
     * @throws IllegalArgumentException 结构不对、某个合约不合法、没有合约或交易代码重复；消息说明是哪一个合约、哪里不对
     */
    static List<ContractSpec> parse(JsonElement root) {
        if (!root.isJsonObject() || !root.getAsJsonObject().has("contracts")
                || !root.getAsJsonObject().get("contracts").isJsonArray()) {
            throw new IllegalArgumentException("顶层必须是 {\"contracts\": [...]}");
        }
        JsonArray contracts = root.getAsJsonObject().getAsJsonArray("contracts");
        if (contracts.isEmpty()) {
            throw new IllegalArgumentException("至少要配置一个合约");
        }
        List<ContractSpec> specs = new ArrayList<>();
        Set<String> symbols = new HashSet<>();
        for (int index = 0; index < contracts.size(); index++) {
            ContractSpec spec = parseContract(contracts.get(index), index + 1);
            if (!symbols.add(spec.symbol())) {
                throw new IllegalArgumentException("合约的交易代码重复：" + spec.symbol());
            }
            specs.add(spec);
        }
        return List.copyOf(specs);
    }

    private static ContractSpec parseContract(JsonElement element, int position) {
        try {
            DataResult<ContractSpec> result = CONTRACT_CODEC.parse(JsonOps.INSTANCE, element);
            return result.getOrThrow(IllegalArgumentException::new);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 字段缺失或类型不对由 Codec 报告，数值不合法由 ContractSpec 的校验报告
            throw new IllegalArgumentException("第 " + position + " 个合约：" + e.getMessage(), e);
        }
    }

    private static void writeDefaults(Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, toJson(ContractSpec.defaults()), StandardCharsets.UTF_8);
            LOGGER.info("合约配置 {} 不存在，已按内置的 DIAMONDCRP 与 GUNPOWDERCRP 生成", file);
        } catch (IOException e) {
            LOGGER.warn("无法生成合约配置 {}，本次运行使用内置的合约", file, e);
        }
    }
}
