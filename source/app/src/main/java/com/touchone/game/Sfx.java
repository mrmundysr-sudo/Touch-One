package com.touchone.game;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Thin SoundPool wrapper. Sounds are only played once their sample has finished
 * decoding (an unloaded sample silently drops the call), and every play returns a
 * stream id so a looping/charging sound can be stopped early.
 */
final class Sfx {
    private static final String[] NAMES = {
            "sfx_select", "sfx_click", "sfx_tick", "sfx_deal", "sfx_draw", "sfx_whoosh",
            "sfx_play", "sfx_error", "sfx_turn", "sfx_pull2", "sfx_pull4", "sfx_spin",
            "sfx_jump", "sfx_touch", "sfx_wheel", "sfx_confirm", "sfx_hold",
            "sfx_touchone", "sfx_win", "sfx_loss",
            "sfx_boy_yes", "sfx_boy_ohno", "sfx_girl_yes", "sfx_girl_ohno",
            "sfx_dog_yes", "sfx_dog_ohno"
    };

    private final SoundPool pool;
    private final Map<String, Integer> ids = new HashMap<>();
    private final Set<Integer> ready = Collections.synchronizedSet(new HashSet<Integer>());

    Sfx(Context ctx) {
        pool = new SoundPool.Builder()
                .setMaxStreams(10)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        pool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool sp, int sampleId, int status) {
                if (status == 0) ready.add(sampleId);
            }
        });
        for (String n : NAMES) {
            int res = ctx.getResources().getIdentifier(n, "raw", ctx.getPackageName());
            if (res != 0) ids.put(n, pool.load(ctx, res, 1));
        }
    }

    /** @return stream id, or 0 if the sound wasn't ready. */
    int play(String name, float volume, float rate) {
        Integer id = ids.get(name);
        if (id == null || !ready.contains(id)) return 0;
        float v = Math.max(0f, Math.min(1f, volume));
        return pool.play(id, v, v, 1, 0, Math.max(0.5f, Math.min(2f, rate)));
    }

    int play(String name) {
        return play(name, 1f, 1f);
    }

    void stop(int streamId) {
        if (streamId != 0) pool.stop(streamId);
    }

    void release() {
        pool.release();
    }
}
