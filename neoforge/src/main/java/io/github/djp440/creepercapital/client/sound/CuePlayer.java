package io.github.djp440.creepercapital.client.sound;

import io.github.djp440.creepercapital.client.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * 播放交易界面的音效：事件 → 原版 {@link SoundEvent} 的绑定都在 {@link #soundOf(Cue)} 一处，
 * 音色类别、音量和音调在 {@link Cue}，总开关与音量读客户端配置。只用原版音效，不带自制音频。
 */
public final class CuePlayer {
    private CuePlayer() {
    }

    /** 按客户端配置播放这个事件的音效：总开关关闭或音量为 0 时什么都不播。 */
    public static void play(Cue cue) {
        float volume = CueVolume.of(ClientConfig.soundEnabled(), ClientConfig.soundVolume(), cue);
        if (!CueVolume.audible(volume)) {
            return;
        }
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(soundOf(cue), cue.pitch(), volume));
    }

    /** 这个事件对应的原版音效（switch 穷尽，新增事件不绑定音效就编译不过）。 */
    static SoundEvent soundOf(Cue cue) {
        return switch (cue) {
            case BUTTON_CLICK, TAB_SWITCH, CHART_DISMISS, CHART_CANCEL_ORDER -> SoundEvents.UI_BUTTON_CLICK.value();
            case INPUT_CONFIRM, CHART_CONFIRM -> SoundEvents.UI_LOOM_SELECT_PATTERN;
            case ORDER_OPENED -> SoundEvents.ITEM_PICKUP;
            case ORDER_CLOSED -> SoundEvents.VILLAGER_YES;
            case ORDER_PLACED -> SoundEvents.ITEM_FRAME_ADD_ITEM;
            case ORDER_CANCELLED -> SoundEvents.ITEM_FRAME_REMOVE_ITEM;
            case ORDER_VOIDED -> SoundEvents.ITEM_BREAK.value();
            case LIMIT_FILLED -> SoundEvents.NOTE_BLOCK_CHIME.value();
            case TAKE_PROFIT -> SoundEvents.PLAYER_LEVELUP;
            case STOP_LOSS -> SoundEvents.ANVIL_LAND;
            case LIQUIDATED -> SoundEvents.ANVIL_DESTROY;
            case AUTO_DELEVERAGED -> SoundEvents.ENDERMAN_TELEPORT;
            case REJECTED -> SoundEvents.VILLAGER_NO;
            case OFFLINE_SUMMARY -> SoundEvents.UI_TOAST_IN;
        };
    }
}
