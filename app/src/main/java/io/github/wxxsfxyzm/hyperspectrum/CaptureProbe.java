// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum;

import android.Manifest;
import android.content.Context;
import android.media.AudioManager;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.Locale;

/** One bounded session at a time. No raw audio/FFT buffers are persisted. */
final class CaptureProbe {
    interface Reporter { void report(String message, Throwable failure); }
    private final Reporter reporter;
    private final HandlerThread thread = new HandlerThread("IslandSpectrumProbe");
    private final Handler handler;
    private boolean closed;
    private Visualizer visualizer;
    private FftMetrics.Window window;
    private long sequence;
    private long started;
    private long lastReport;
    private String mode;
    private String key;
    private String route;
    private Boolean playing;
    private boolean failed;
    private int samplingRate;
    private Runnable deadline;

    CaptureProbe(Reporter reporter) {
        this.reporter = reporter;
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    void observe(Context context, String key, Boolean playing, String route, boolean force) {
        handler.post(() -> {
            synchronized (this) {
                if (closed) return;
                if (!force && java.util.Objects.equals(this.key, key)
                        && java.util.Objects.equals(this.playing, playing)
                        && java.util.Objects.equals(this.route, route)) return;
                finish("media_changed");
                this.key = key;
                this.playing = playing;
                this.route = route;
                if (playing == null) {
                    reporter.report("capture_skip reason=unknown_playback_state", null);
                    return;
                }
                if (!(route.isEmpty() || route.equals("local") || route.equals("headset"))) {
                    reporter.report("capture_skip reason=remote_route route=" + route, null);
                    return;
                }
                start(context, playing ? "playing" : "paused", playing ? 12000 : 3000);
            }
        });
    }

    void removed(String removedKey) {
        handler.post(() -> {
            synchronized (this) {
                if (closed || !java.util.Objects.equals(key, removedKey)) return;
                finish("island_removed"); key = null; playing = null; route = null;
            }
        });
    }

    private void start(Context context, String mode, long duration) {
        this.mode = mode;
        failed = false;
        samplingRate = 0;
        window = new FftMetrics.Window();
        started = SystemClock.elapsedRealtime();
        lastReport = started;
        long session = ++sequence;
        AudioManager audio = context.getSystemService(AudioManager.class);
        reporter.report("capture_start session=" + session + " mode=" + mode
                + " durationMs=" + duration + " uid=" + Process.myUid()
                + " recordAudio=" + context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                + " modifyAudio=" + context.checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS)
                + " musicActive=" + (audio != null && audio.isMusicActive())
                + " route=" + route + " audioSession=0", null);
        try {
            visualizer = new Visualizer(0);
            check("disable", visualizer.setEnabled(false));
            int[] sizes = Visualizer.getCaptureSizeRange();
            int size = 512;
            while (size < sizes[0]) size *= 2;
            while (size > sizes[1] && size > 1) size /= 2;
            check("capture_size", visualizer.setCaptureSize(size));
            check("scaling", visualizer.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED));
            int rate = Math.min(20000, Visualizer.getMaxCaptureRate()); // milliHertz: <=20 Hz.
            check("listener", visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer v, byte[] data, int rate) {}
                @Override public void onFftDataCapture(Visualizer v, byte[] data, int rate) {
                    synchronized (CaptureProbe.this) {
                        if (closed || session != sequence || v != visualizer || window == null) return;
                        try {
                            samplingRate = rate;
                            window.add(data);
                            long now = SystemClock.elapsedRealtime();
                            if (now - lastReport >= 1000) {
                                lastReport = now;
                                reporter.report(summary("capture_tick", "sampling")
                                        + " bands=" + Arrays.toString(FftMetrics.bands(data, 6)), null);
                            }
                        } catch (RuntimeException failure) {
                            failed = true;
                            reporter.report("capture_error stage=fft session=" + session, failure);
                            finish("invalid_fft");
                        }
                    }
                }
            }, rate, false, true));
            check("enable", visualizer.setEnabled(true));
            reporter.report("capture_ready session=" + session + " size=" + size
                    + " rateMilliHz=" + rate + " scaling=as_played", null);
            deadline = () -> {
                synchronized (CaptureProbe.this) {
                    if (!closed && sequence == session) finish("deadline");
                }
            };
            handler.postDelayed(deadline, duration);
        } catch (RuntimeException failure) {
            failed = true;
            reporter.report("capture_error stage=setup session=" + session, failure);
            finish("setup_failed");
        }
    }

    private static void check(String operation, int status) {
        if (status != Visualizer.SUCCESS) throw new IllegalStateException(operation + " status=" + status);
    }

    private String summary(String event, String reason) {
        return String.format(Locale.ROOT,
                "%s session=%d mode=%s reason=%s durationMs=%d frames=%d nonzero=%d changed=%d peak=%d meanEnergy=%.4f signal=%s samplingRateMilliHz=%d",
                event, sequence, mode, reason, SystemClock.elapsedRealtime() - started,
                window.frames, window.nonzeroFrames, window.changedFrames, window.peak,
                window.meanEnergy(), failed ? "error" : window.signal(), samplingRate);
    }

    private void finish(String reason) {
        if (deadline != null) handler.removeCallbacks(deadline);
        deadline = null;
        Visualizer old = visualizer;
        visualizer = null; // Reject already-queued callbacks before releasing the native object.
        if (old != null) {
            try { old.setEnabled(false); }
            catch (RuntimeException e) { reporter.report("capture_error stage=disable", e); }
            try { old.release(); }
            catch (RuntimeException e) { reporter.report("capture_error stage=release", e); }
        }
        if (window != null) reporter.report(summary("capture_summary", reason), null);
        window = null;
    }

    synchronized void close() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        finish("hot_reload");
        thread.quitSafely();
    }
}
