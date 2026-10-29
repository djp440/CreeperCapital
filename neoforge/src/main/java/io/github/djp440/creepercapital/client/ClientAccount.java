package io.github.djp440.creepercapital.client;

import io.github.djp440.creepercapital.core.account.Assets;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.network.AccountPayload;

/**
 * 客户端缓存的本人资产，只由服务端同步过来的数据更新（登录时与余额变动时）；离开服务器时清空。
 */
public final class ClientAccount {
    private static Assets assets = Assets.ofWallet(Money.ZERO);

    private ClientAccount() {
    }

    static void accept(AccountPayload payload) {
        assets = payload.assets();
    }

    static void clear() {
        assets = Assets.ofWallet(Money.ZERO);
    }

    public static Assets assets() {
        return assets;
    }
}
