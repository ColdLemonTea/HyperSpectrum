// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum;

import android.content.Context;
import android.media.AudioManager;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.util.Arrays;

/** Shared continuous capture; UI receives only the latest frame, never an unbounded queue. */
final class LiveSpectrum {
    interface Sink { void frame(float[] levels, long uptime, boolean healthy); }
    private final CaptureProbe.Reporter reporter;
    private final Sink sink;
    private final HandlerThread thread = new HandlerThread("IslandSpectrumLive");
    private final Handler worker;
    private final Handler main;
    private boolean closed;
    private boolean demanded;
    private Visualizer visualizer;
    private long generation;
    private long started, lastFrame, lastLog;
    private long frames;
    private float[] latest = new float[SpectrumDsp.BARS];
    private boolean deliveryQueued;
    private int subscribers;
    private AudioManager audio;
    private PlaybackSource playbackSource;
    private PlaybackSource.State source;
    private int captureSession;
    private int sourceChannels, captureChannels;
    private double channelGain = 1;
    private OutputRoute outputRoute;
    private OutputRoute.State output;
    private final VolumeCompensation compensation = new VolumeCompensation();
    private double outputGain = 1;
    private long lastVolume;
    private int volume, maxVolume;
    private boolean muted;
    private long lastVolumeError, captureAfter, retryAfter;
    private final Visualizer.MeasurementPeakRms measurement = new Visualizer.MeasurementPeakRms();
    private boolean measurementReady;
    private boolean normalized;
    private SpectrumDsp dsp;
    private BassResponse bass;
    private final float[] minimum = new float[SpectrumDsp.BARS], maximum = new float[SpectrumDsp.BARS];
    private final double[] sum = new double[SpectrumDsp.BARS];
    private final int[] nearTop = new int[SpectrumDsp.BARS];
    private int windowFrames;
    private int minimumRms, maximumRms;
    private long fftMaxUs;
    private final Runnable watchdog = this::watchdog;
    private final Runnable deliver = this::deliver;
    private final Runnable outputPoll = this::pollOutput;

    LiveSpectrum(CaptureProbe.Reporter reporter, Handler main, Sink sink) {
        this.reporter = reporter; this.main = main; this.sink = sink;
        thread.start(); worker = new Handler(thread.getLooper());
    }

    void execute(Runnable action) {
        worker.post(() -> {
            synchronized (this) { if (closed) return; }
            action.run();
        });
    }

    void audio(Context context) {
        execute(() -> {
            audio = context.getSystemService(AudioManager.class);
            outputRoute = audio == null ? null : new OutputRoute(audio);
            playbackSource = audio == null ? null : new PlaybackSource(context, audio);
        });
    }

    void media(String packageName, int userId) {
        execute(() -> {
            if (playbackSource == null) return;
            try { playbackSource.media(packageName, userId); }
            catch (ReflectiveOperationException | RuntimeException failure) {
                reporter.report("spectrum_media_uid_unavailable", failure);
            }
        });
    }

    private boolean source() {
        PlaybackSource.State next;
        Throwable error = null;
        try {
            next = playbackSource == null ? new PlaybackSource.State(-1, -1, false, "audio_unavailable")
                    : playbackSource.read();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            next = new PlaybackSource.State(-1, -1, false, "query_" + failure.getClass().getSimpleName());
            error = failure;
        }
        boolean changed = !next.equals(source);
        if (changed) reporter.report("spectrum_source sessionId=" + next.sessionId() + " uid=" + next.uid()
                + " spatial=" + next.spatial() + " reason=" + next.reason()
                + " sourceChannels=" + next.sourceChannels() + " captureChannels=" + next.captureChannels(), error);
        source = next;
        return changed;
    }

    private boolean volume(long now, boolean force) {
        if (outputRoute == null || (!force && now - lastVolume < 200)) return false;
        lastVolume = now;
        try {
            OutputRoute.State next = outputRoute.read(source == null ? -1 : source.uid(),
                    visualizer == null ? -1 : captureSession, generation);
            boolean changed = output != null && (!output.key.equals(next.key) || output.mode != next.mode);
            boolean log = output == null || changed || output.volume != next.volume
                    || output.muted != next.muted || !output.reason.equals(next.reason)
                    || Float.compare(output.appliedDb, next.appliedDb) != 0;
            output = next;
            volume = next.volume; maxVolume = next.maximum; muted = next.muted;
            double gain = BuildConfig.ROUTE_COMPENSATION ? next.gain() : 1;
            compensation.target(gain, now, force || changed);
            if (log) reporter.report("spectrum_output type=" + next.type + " behavior=" + next.behavior
                    + " captureVolume=" + next.mode + " reason=" + next.reason
                    + " volume=" + volume + "/" + maxVolume + " muted=" + muted
                    + " volumeDb=" + next.volumeDb + " maximumDb=" + next.maximumDb
                    + " appliedDb=" + next.appliedDb + " policyOutput=" + next.policyOutput
                    + " desiredGain=" + next.gain() + " compensation=" + BuildConfig.ROUTE_COMPENSATION, null);
            return changed;
        } catch (RuntimeException failure) {
            // Do not carry inverse software volume through a failed route query.
            compensation.target(VolumeCompensation.DISPLAY_GAIN, now, true);
            if (now - lastVolumeError >= 5000) {
                lastVolumeError = now;
                reporter.report("spectrum_volume_unavailable", failure);
            }
            return false;
        }
    }

    void demand(boolean enabled, int count) {
        worker.post(() -> {
            synchronized (this) {
                if (closed) return;
                subscribers = count;
                demanded = enabled;
                if (!enabled) { stop("no_visible_playback"); return; }
                if (visualizer == null) {
                    volume(SystemClock.uptimeMillis(), true);
                    source();
                    if (source.sessionId() >= 0) start(false);
                }
                worker.removeCallbacks(outputPoll);
                worker.postDelayed(outputPoll, 200);
            }
        });
    }

    private synchronized void pollOutput() {
        if (closed || !demanded) return;
        long now = SystemClock.uptimeMillis();
        boolean changed = volume(now, false);
        boolean sourceChanged = source();
        if (changed || sourceChanged) {
            stop(sourceChanged ? "capture_source_changed" : "output_changed", source.sessionId() < 0);
            retryAfter = 0;
        }
        if (visualizer == null && source.sessionId() >= 0 && now >= retryAfter) start(changed || sourceChanged);
        worker.postDelayed(outputPoll, 200);
    }

    private void start(boolean keepFrame) {
        long session = ++generation;
        try {
            captureSession = source.sessionId();
            sourceChannels = source.sourceChannels(); captureChannels = source.captureChannels();
            visualizer = new Visualizer(captureSession);
            check(visualizer.setEnabled(false));
            int[] range = Visualizer.getCaptureSizeRange();
            int size = 1024;
            while (size < range[0]) size *= 2;
            while (size > range[1]) size /= 2;
            check(visualizer.setCaptureSize(size));
            measurementReady = visualizer.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS) == Visualizer.SUCCESS;
            normalized = measurementReady;
            check(visualizer.setScalingMode(normalized ? Visualizer.SCALING_MODE_NORMALIZED : Visualizer.SCALING_MODE_AS_PLAYED));
            dsp = new SpectrumDsp(size); bass = new BassResponse(); resetRange();
            check(visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer v, byte[] bytes, int rate) {
                    synchronized (LiveSpectrum.this) {
                        if (closed || session != generation || v != visualizer) return;
                        long now = SystemClock.uptimeMillis();
                        if (now < captureAfter) return;
                        try {
                            if (measurementReady && v.getMeasurementPeakRms(measurement) != Visualizer.SUCCESS) {
                                // Without raw RMS, normalized capture would amplify quiet passages again.
                                measurementReady = normalized = false;
                                check(v.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED));
                                reporter.report("spectrum_measurement_unavailable fallback=as_played", null);
                                return; // The current waveform still belongs to the previous scaling mode.
                            }
                            if (normalized && compensation.settling(now)) {
                                // Keep the last real frame through software-volume transitions.
                                // Capture stays active so the old RMS buffers can expire. This is
                                // healthy pending data, rather than a stock-animation fallback.
                                lastFrame = now; queueDelivery(); return;
                            }
                            long computeStart = SystemClock.elapsedRealtimeNanos();
                            channelGain = CaptureChannels.gain(sourceChannels, captureChannels, normalized);
                            outputGain = compensation.value(now) * channelGain;
                            // Stream mute is a playback setting. Render any real captured signal;
                            // actual silent PCM and pause are handled by the DSP and view lifecycle.
                            latest = dsp.levels(bytes, rate, normalized, measurement.mRms, false, outputGain);
                            latest[0] = bass.level(dsp.bandAmplitudes()[0], now);
                            fftMaxUs = Math.max(fftMaxUs, (SystemClock.elapsedRealtimeNanos() - computeStart) / 1000);
                        }
                        catch (RuntimeException failure) {
                            reporter.report("live_capture_error reason=invalid_fft", failure);
                            stop("invalid_fft"); return;
                        }
                        lastFrame = now;
                        frames++;
                        windowFrames++;
                        for (int i = 0; i < latest.length; i++) {
                            minimum[i] = Math.min(minimum[i], latest[i]); maximum[i] = Math.max(maximum[i], latest[i]);
                            sum[i] += latest[i];
                            if (latest[i] >= 0.95f) nearTop[i]++;
                        }
                        if (measurementReady) {
                            minimumRms = Math.min(minimumRms, measurement.mRms);
                            maximumRms = Math.max(maximumRms, measurement.mRms);
                        }
                        queueDelivery();
                        if (lastFrame - lastLog >= 5000) {
                            lastLog = lastFrame;
                            float[] meanLevels = new float[SpectrumDsp.BARS], nearTopPercent = new float[SpectrumDsp.BARS];
                            for (int i = 0; i < latest.length; i++) {
                                meanLevels[i] = (float) (sum[i] / windowFrames);
                                nearTopPercent[i] = nearTop[i] * 100f / windowFrames;
                            }
                            reporter.report("live_capture_tick generation=" + generation
                                    + " sessionId=" + captureSession
                                    + " frames=" + frames + " subscribers=" + subscribers
                                    + " volume=" + volume + "/" + maxVolume + " muted=" + muted
                                    + " measurement=" + measurementReady + " rmsMb=" + measurement.mRms
                                    + " peakMb=" + measurement.mPeak + " waveformRms=" + dsp.waveformRms()
                                    + " restoreGain=" + dsp.restoreGain() + " fftMaxUs=" + fftMaxUs
                                    + " outputGain=" + outputGain
                                    + " channelGain=" + channelGain
                                    + " outputType=" + (output == null ? 0 : output.type)
                                    + " captureVolume=" + (output == null ? "UNKNOWN" : output.mode)
                                    + " bassOnsets=" + bass.onsets() + " bassPeak=" + bass.peak()
                                    + " bassBase=" + SpectrumDsp.mapAmplitude(0, dsp.bandAmplitudes()[0])
                                    + " sampleRateHz=" + rate / 1000 + " windowFrames=" + windowFrames
                                    + " bands=" + Arrays.toString(dsp.bandAmplitudes())
                                    + " levels=" + Arrays.toString(latest)
                                    + " minimum=" + Arrays.toString(minimum) + " maximum=" + Arrays.toString(maximum)
                                    + " meanLevels=" + Arrays.toString(meanLevels)
                                    + " nearTopPercent=" + Arrays.toString(nearTopPercent)
                                    + " rmsRange=" + minimumRms + ":" + maximumRms, null);
                            resetRange();
                        }
                    }
                }
                @Override public void onFftDataCapture(Visualizer v, byte[] bytes, int rate) { }
            }, Math.min(20000, Visualizer.getMaxCaptureRate()), true, false));
            check(visualizer.setEnabled(true));
            started = lastLog = SystemClock.uptimeMillis();
            retryAfter = 0;
            captureAfter = started + (keepFrame ? 250 : 100);
            if (!keepFrame) lastFrame = 0;
            frames = 0;
            worker.postDelayed(watchdog, 1000);
            reporter.report("live_capture_start generation=" + generation + " subscribers=" + subscribers
                    + " sessionId=" + captureSession + " spatial=" + source.spatial()
                    + " sourceChannels=" + sourceChannels + " captureChannels=" + captureChannels
                    + " scaling=" + (normalized ? "normalized+raw_rms" : "as_played")
                    + " fft=float captureSize=" + size, null);
        } catch (RuntimeException failure) {
            reporter.report("live_capture_error", failure); stop("setup_failed");
        }
    }

    private static void check(int status) {
        if (status != Visualizer.SUCCESS) throw new IllegalStateException("Visualizer status=" + status);
    }

    private void resetRange() {
        Arrays.fill(minimum, 1); Arrays.fill(maximum, 0);
        Arrays.fill(sum, 0); Arrays.fill(nearTop, 0); windowFrames = 0;
        minimumRms = Integer.MAX_VALUE; maximumRms = Integer.MIN_VALUE; fftMaxUs = 0;
    }

    private void queueDelivery() {
        if (!deliveryQueued) { deliveryQueued = true; main.post(deliver); }
    }

    private void deliver() {
        float[] levels; long time; boolean healthy;
        synchronized (this) {
            deliveryQueued = false;
            if (closed) return;
            levels = latest; time = lastFrame;
            healthy = visualizer != null && time != 0 && SystemClock.uptimeMillis() - time < 750;
        }
        sink.frame(levels, time, healthy);
    }

    private synchronized void watchdog() {
        if (closed || visualizer == null) return;
        if (SystemClock.uptimeMillis() - Math.max(started, lastFrame) > 1500) {
            reporter.report("live_capture_error reason=no_waveform_callbacks", null);
            stop("stalled");
        } else worker.postDelayed(watchdog, 1000);
    }

    private void stop(String reason) {
        stop(reason, true);
    }

    private void stop(String reason, boolean clearFrame) {
        worker.removeCallbacks(watchdog);
        if (!demanded) worker.removeCallbacks(outputPoll);
        retryAfter = SystemClock.uptimeMillis() + 3000;
        Visualizer old = visualizer; visualizer = null;
        if (clearFrame) { latest = new float[SpectrumDsp.BARS]; lastFrame = 0; }
        if (old != null) {
            try { old.setEnabled(false); } catch (RuntimeException ignored) {}
            try { old.setDataCaptureListener(null, 0, false, false); } catch (RuntimeException ignored) {}
            try { old.release(); }
            catch (RuntimeException failure) { reporter.report("live_capture_error stage=release", failure); }
            reporter.report("live_capture_stop reason=" + reason + " frames=" + frames
                    + " sessionId=" + captureSession, null);
        }
        if (clearFrame) queueDelivery();
    }

    synchronized void close() {
        closed = true;
        worker.removeCallbacksAndMessages(null);
        stop("hot_reload");
        main.removeCallbacks(deliver);
        thread.quitSafely();
    }
}
