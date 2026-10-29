package io.github.djp440.creepercapital.client.sound;

/** 音效总开关与音量设置折算成一次播放的音量。 */
public final class CueVolume {
    private CueVolume() {
    }

    /**
     * 这次播放的音量：关闭时为 0，否则是事件的基础音量乘以音量设置（夹在 0 到 1 之间，非数字当 0）。
     */
    public static float of(boolean enabled, double volume, Cue cue) {
        if (!enabled || Double.isNaN(volume)) {
            return 0f;
        }
        return cue.baseVolume() * (float) Math.max(0.0, Math.min(1.0, volume));
    }

    /** 这个音量是否需要播放。 */
    public static boolean audible(float volume) {
        return volume > 0f;
    }
}
