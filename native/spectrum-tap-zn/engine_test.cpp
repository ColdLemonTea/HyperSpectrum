// SPDX-License-Identifier: Apache-2.0
#include "engine.h"
#include "identity.h"
#include "lazy_engine.h"
#include <array>
#include <cassert>
#include <limits>
#include <thread>
#include <cmath>
#include <algorithm>
#include <vector>

using namespace tap;
namespace {
constexpr uint64_t now = 10000000000ull;
Metadata meta() { return {10343,425,131,93,6,1,3,0,5,2,3,44100,8,false}; }
Command command() {
    Command c; c.instance = 42; c.sequence = 1; c.uid = 10343;
    c.session = 425; c.io = 93; c.seconds = 10; c.not_after_ns = now + 5000000000; return c;
}
void gates_and_energy() {
    Engine e; Report r; auto m = meta(); auto c = command();
    const std::array<float, 4> stereo{0.5f,-0.5f,0.5f,-0.5f};
    e.observe(m, stereo.data(), 2, 10, now); e.drain(r);
    assert(r.tracks[0].blocks == 0);
    assert(e.configure(c, now));
    e.observe(m, stereo.data(), 2, 10, now + 1); e.drain(r); e.counters(r);
    assert(r.entered == 1 && r.eligible == 1);
    assert(r.tracks[0].frames == 2 && r.tracks[0].rms_last == 0.5);
    assert(r.tracks[0].peak == 0.5 && r.tracks[0].nonzero == 4);
    // Anti-phase stereo retains energy; no signed channel summing.
    const std::array<float, 4> zero{};
    e.observe(m, zero.data(), 2, 10, now + 2); e.drain(r);
    assert(r.tracks[0].rms_last == 0 && r.tracks[0].changed == 1);
    m.state = 8; e.observe(m, stereo.data(), 2, 10, now + 3);
    m.state = 6; m.app_muted = true; e.observe(m, stereo.data(), 2, 10, now + 4);
    m.app_muted = false; ++m.session; e.observe(m, stereo.data(), 2, 10, now + 5);
    --m.session; e.observe(m, stereo.data(), 2, 10, now + 10000000000ull);
    e.drain(r); e.counters(r);
    assert(r.tracks[0].blocks == 2 && r.rejected == 1 && r.app_muted == 1);
    e.stop(); e.observe(m, stereo.data(), 2, 10, now + 6); e.drain(r);
    assert(r.tracks[0].blocks == 2);
}
void drops_and_format() {
    Engine e; Report r; auto m = meta(); assert(e.configure(command(), now));
    const std::array<float, 2> pcm{0.25f,-0.25f};
    for (uint32_t i = 0; i < kBlocks + 1; ++i) e.observe(m, pcm.data(), 1, 10, now + i);
    e.observe(m, pcm.data(), 1, 20, now + 6); // Different producer cannot race PCM writes.
    e.drain(r); assert(r.tracks[0].blocks == 4 && r.tracks[0].drops == 1);
    assert(r.tracks[0].contention == 1);
    e.observe(m, pcm.data(), 1, 10, now + 7); e.drain(r);
    assert(r.tracks[0].discontinuities == 1);
    // Oversize must not read beyond even this tiny buffer (ASan validates).
    e.observe(m, pcm.data(), kFrames + 1, 10, now + 8); e.counters(r);
    assert(r.oversize == 1);
    m.format = 1; m.frame_size = 4;
    const std::array<int16_t, 2> short_pcm{16384,-16384};
    e.observe(m, short_pcm.data(), 1, 10, now + 9); e.drain(r);
    assert(r.tracks[0].rms_last == 0.5 && r.tracks[0].format == 1);
    assert(r.tracks[0].discontinuities == 2);
    m.format = 5; m.frame_size = 8;
    const std::array<float, 2> invalid{std::numeric_limits<float>::quiet_NaN(),0};
    e.observe(m, invalid.data(), 1, 10, now + 10); e.drain(r);
    assert(r.tracks[0].invalid_samples == 1 && r.tracks[0].rms_last == 0);
    auto c = command(); assert(e.configure(c, now + 20)); r = Report{};
    e.observe(meta(), pcm.data(), 1, 20, now + 21); e.drain(r);
    assert(r.tracks[0].owner_tid == 20 && r.tracks[0].blocks == 1 && r.tracks[0].drops == 0);
}
void quiescence() {
    Engine e; const auto m = meta(); const auto c = command();
    std::atomic<bool> done{false}; const std::array<float, 2> pcm{1,-1};
    std::thread producer([&] { while (!done.load()) e.observe(m, pcm.data(), 1, 10, now + 1); });
    for (unsigned i = 0; i < 100; ++i) {
        assert(e.configure(c, now)); Report r; e.drain(r);
    }
    e.stop(); done.store(true); producer.join();
    assert(e.configure(Command{}, now)); Report r; e.drain(r);
    assert(r.tracks[0].blocks == 0);
}
void protocol() {
    auto c = command(); assert(valid(c, 42, 0, now));
    assert(!valid(c, 43, 0, now) && !valid(c, 42, 1, now));
    assert(!valid(c, 42, 0, c.not_after_ns));
    assert(!valid(c, 42, 0, now - 1)); // Far-future request also rejected.
    c.seconds = 61; assert(!valid(c, 42, 0, now));
    c.seconds = 1; c.io = 213; assert(valid(c, 42, 0, now));
    c.io = 0; assert(valid(c, 42, 0, now));
    c.io = UINT32_MAX; assert(!valid(c, 42, 0, now));
    c.seconds = 0; assert(valid(c, 42, 0, now));
    c.reserved = 1; assert(!valid(c, 42, 0, now));
}
void fft_signal() {
    SpectrumFft fft;
    std::array<float, SpectrumFft::N * 2> tone{};
    constexpr double pi = 3.14159265358979323846;
    // A bin-centred 750 Hz tone at 48kHz; anti-phase stereo must not cancel.
    for (size_t i = 0; i < SpectrumFft::N; ++i) {
        tone[i * 2] = float(0.4 * std::sin(2 * pi * 64 * i / SpectrumFft::N));
        tone[i * 2 + 1] = -tone[i * 2];
    }
    SpectrumFrame full;
    assert(!fft.push(reinterpret_cast<unsigned char*>(tone.data()), 2048, 5, 2, 48000, now, full));
    assert(fft.push(reinterpret_cast<unsigned char*>(tone.data() + 4096), 2048, 5, 2, 48000, now + 1, full));
    assert(std::max_element(full.bands, full.bands + 6) - full.bands == 3);
    assert(std::abs(full.rms - 0.4 / std::sqrt(2)) < 0.00001);
    const float amplitude = full.bands[3];
    // Independent direct-DFT oracle: 2048 Hann inputs zero-padded to4096,
    // magnitude sum over527..1500Hz, including the real-FFT factor2.
    assert(std::abs(amplitude - 2 * 0.8240598203440719) < 0.00001);
    fft.reset(); for (auto& sample : tone) sample *= 0.1f;
    SpectrumFrame quiet;
    assert(fft.push(reinterpret_cast<unsigned char*>(tone.data()), SpectrumFft::N, 5, 2, 48000, now, quiet));
    assert(std::abs(quiet.bands[3] / amplitude - 0.1) < 0.00001);
    // A channel/rate transition discards the old window rather than splicing it.
    assert(!fft.push(reinterpret_cast<unsigned char*>(tone.data()), 1024, 5, 1, 44100, now, quiet));
    fft.reset(); std::fill(tone.begin(), tone.end(), 0);
    assert(fft.push(reinterpret_cast<unsigned char*>(tone.data()), SpectrumFft::N, 5, 2, 48000, now, quiet));
    assert(quiet.rms == 0);
    for (float band : quiet.bands) assert(band == 0);
}
void apple_bands_and_cadence() {
    std::array<float, SpectrumFft::N> pcm{};
    constexpr double pi = 3.14159265358979323846;
    // Bin-centred tones well inside each recovered Apple six-bar interval.
    const unsigned bins[]{4,10,30,64,200,600};
    const double oracle[]{0.4234075866435125,0.4834807065212236,0.514618933696035,
                          0.5150373877150449,0.5152412903245955,0.5152553210286379};
    for (unsigned band = 0; band < 6; ++band) {
        SpectrumFft fft; SpectrumFrame f;
        for (size_t i = 0; i < pcm.size(); ++i) pcm[i] = float(0.25 * std::sin(2 * pi * bins[band] * i / pcm.size()));
        assert(fft.push(reinterpret_cast<unsigned char*>(pcm.data()), pcm.size(), 5, 1, 48000, now, f));
        assert(std::max_element(f.bands, f.bands + 6) - f.bands == band);
        assert(std::abs(f.bands[band] - 2 * oracle[band]) < 0.00001);
        const auto sequence = f.sequence;
        for (unsigned hop = 0; hop < 3; ++hop)
            assert(!fft.push(reinterpret_cast<unsigned char*>(pcm.data() + hop * 1024), 1024, 5, 1, 48000, now + hop + 1, f));
        assert(fft.push(reinterpret_cast<unsigned char*>(pcm.data() + 3072), 1024, 5, 1, 48000, now + 4, f));
        assert(f.sequence == sequence + 1);
        fft.reset();
        std::fill(pcm.begin() + SpectrumFft::INPUT, pcm.end(), 16);
        assert(fft.push(reinterpret_cast<unsigned char*>(pcm.data()), pcm.size(), 5, 1, 48000, now, f));
        assert(std::abs(f.bands[band] - 2 * oracle[band]) < 0.00001); // Trailing tap half is not analysis input.
    }
}
void apple_accelerate_reference() {
    // Generated offline on macOS using the public
    // Accelerate FFT.forward, Hann window, zvmags-equivalent magnitudes and
    // MediaCoreUI normalization. Tolerance covers float-vs-double arithmetic,
    // not an arbitrary display-gain difference. Include packed DC/Nyquist.
    struct Fixture { unsigned bin; float bands[6]; };
    const Fixture fixtures[]{
        {0, {1.271421909f, 0.006868627388f, 0.002061452484f, 0.0001444597292f, 1.791355498e-05f, 1.833984129e-06f}},
        {4, {0.8468152285f, 0.1745418608f, 0.001871723798f, 2.927065543e-05f, 1.567204094e-06f, 4.966312304e-07f}},
        {10, {0.009986205027f, 0.9669613838f, 0.05263893679f, 8.004652773e-05f, 3.697859711e-06f, 8.735134998e-07f}},
        {30, {0.000153933157f, 0.0002485977893f, 1.029237986f, 0.0007765481714f, 1.061471085e-05f, 3.472735443e-06f}},
        {64, {1.487336158e-05f, 1.67827875e-05f, 0.0003547124797f, 1.030075669f, 4.146480933e-05f, 6.137620858e-06f}},
        {200, {5.429776024e-07f, 5.600986128e-07f, 3.582111503e-06f, 2.450254942e-05f, 1.030487537f, 2.41622165e-05f}},
        {600, {2.979282385e-07f, 2.313343259e-07f, 3.438446129e-06f, 1.203646116e-05f, 4.007350799e-05f, 1.030534744f}},
        {2048, {0.5f, 4.367173734e-09f, 2.819250078e-08f, 5.743877551e-08f, 1.806508436e-07f, 3.310295256e-07f}},
    };
    constexpr double pi = 3.14159265358979323846;
    std::array<float, SpectrumFft::N> pcm{};
    for (const auto& fixture : fixtures) {
        for (size_t i = 0; i < pcm.size(); ++i) {
            const double wave = fixture.bin == 0 ? 1 : fixture.bin == SpectrumFft::N / 2
                    ? (i % 2 ? -1 : 1) : std::sin(2 * pi * fixture.bin * i / pcm.size());
            pcm[i] = float(0.25 * wave);
        }
        SpectrumFft fft; SpectrumFrame frame;
        assert(fft.push(reinterpret_cast<unsigned char*>(pcm.data()), pcm.size(), 5, 1, 48000, now, frame));
        for (unsigned band = 0; band < 6; ++band)
            assert(std::abs(frame.bands[band] - fixture.bands[band]) < 0.0001);
    }
}
void multichannel_energy() {
    constexpr double pi = 3.14159265358979323846;
    std::vector<float> mono(SpectrumFft::N), pcm(SpectrumFft::N * kMaxChannels);
    for (size_t i = 0; i < mono.size(); ++i) {
        mono[i] = float(0.25 * std::sin(2 * pi * 64 * i / mono.size()));
        for (unsigned c = 0; c < kMaxChannels; ++c) pcm[i * kMaxChannels + c] = (c % 2 ? -1 : 1) * mono[i];
    }
    SpectrumFft monoFft, spatialFft; SpectrumFrame reference, actual;
    assert(monoFft.push(reinterpret_cast<unsigned char*>(mono.data()), mono.size(), 5, 1, 48000, now, reference));
    assert(spatialFft.push(reinterpret_cast<unsigned char*>(pcm.data()), mono.size(), 5, 12, 48000, now, actual));
    assert(actual.channels == 12 && std::abs(actual.rms - reference.rms) < 0.00001);
    for (unsigned band = 0; band < 6; ++band) assert(std::abs(actual.bands[band] - reference.bands[band]) < 0.00001);
    // Content in the last spatial channel must not be dropped or mixed away.
    spatialFft.reset(); std::fill(pcm.begin(), pcm.end(), 0);
    for (size_t i = 0; i < mono.size(); ++i) pcm[i * kMaxChannels + 11] = mono[i];
    assert(spatialFft.push(reinterpret_cast<unsigned char*>(pcm.data()), mono.size(), 5, 12, 48000, now, actual));
    assert(std::abs(actual.bands[3] * std::sqrt(12) - reference.bands[3]) < 0.00001);
    std::vector<int16_t> shortPcm(pcm.size());
    for (size_t i = 0; i < pcm.size(); ++i) shortPcm[i] = int16_t(std::lround(pcm[i] * 32768));
    spatialFft.reset();
    assert(spatialFft.push(reinterpret_cast<unsigned char*>(shortPcm.data()), mono.size(), 1, 12, 48000, now, actual));
    assert(std::abs(actual.bands[3] * std::sqrt(12) - reference.bands[3]) < 0.001);
    // More than the bounded channel count is rejected before any sample read.
    assert(!spatialFft.push(reinterpret_cast<unsigned char*>(shortPcm.data()), mono.size(), 1, 13, 48000, now, actual));
}
void multichannel_copy_guards() {
    Engine e; Report r; auto m = meta(); auto c = command();
    m.channels = 12; m.mask = 0x2d63f; m.frame_size = 48;
    std::vector<float> pcm(kFrames * kMaxChannels, 0.25f);
    assert(e.configure(c, now));
    e.observe(m, pcm.data(), kFrames, 10, now + 1); e.drain(r);
    assert(r.tracks[0].channels == 12 && r.tracks[0].samples == kFrames * 12 && r.tracks[0].rms_last == 0.25);
    m.format = 1; m.frame_size = 24;
    std::vector<int16_t> shortPcm(kFrames * kMaxChannels, 8192);
    e.observe(m, shortPcm.data(), kFrames, 10, now + 2); e.drain(r);
    assert(r.tracks[0].rms_last == 0.25 && r.tracks[0].blocks == 2);
    m.mask = 3; e.observe(m, shortPcm.data(), kFrames, 10, now + 3); // Count mismatch.
    m.mask = 0x80000fff; e.observe(m, shortPcm.data(), kFrames, 10, now + 4); // Index representation.
    m.mask = 0x2d63f; m.frame_size = 22; e.observe(m, shortPcm.data(), kFrames, 10, now + 5);
    m.frame_size = 24; m.type = 1; e.observe(m, shortPcm.data(), kFrames, 10, now + 6);
    m.type = 0; m.channels = 13; e.observe(m, shortPcm.data(), kFrames, 10, now + 7);
    e.drain(r); e.counters(r);
    assert(r.tracks[0].blocks == 2 && r.rejected == 5);
    // Full12-channel Float32 block is exactly the RT copy bound. Oversize
    // frames must not read even a single byte from this deliberately tiny source.
    m.channels = 12; m.format = 5; m.frame_size = 48;
    const float tiny = 1;
    e.observe(m, &tiny, kFrames + 1, 10, now + 8); e.counters(r);
    assert(r.oversize == 1);
}
void renewal() {
    Engine e; Report report; auto c = command(); assert(e.configure(c, now));
    std::array<float, 2> pcm{0.5,-0.5};
    e.observe(meta(), pcm.data(), 1, 10, now + 1); e.drain(report);
    assert(e.renew(c, now + 2)); e.observe(meta(), pcm.data(), 1, 10, now + 3); e.drain(report);
    assert(report.tracks[0].blocks == 2);
    e.stop(); assert(e.clear_signal()); e.drain(report); assert(report.spectrum.sequence == 0);
    assert(!e.renew(c, now + 4));
    c.reserved = 1; c.client_generation = 1; c.seconds = 2; assert(valid(c, 42, 0, now));
    c.seconds = 3; assert(!valid(c, 42, 0, now));
}
void identities() {
    constexpr char name[] = "com.android.systemui\0\0\0\0";
    assert(exact_identity(name, sizeof(name), "com.android.systemui"));
    constexpr char extra[] = "com.android.systemui\0other";
    assert(!exact_identity(extra, sizeof(extra), "com.android.systemui"));
    assert(!exact_identity("com.android.systemui.fake", 25, "com.android.systemui"));
}
void changing_outputs() {
    Engine e; Report r; auto c = command(); c.io = 0;
    auto m = meta(); m.io = 213;
    std::array<float, kFrames * 2> pcm{}; pcm.fill(0.25f);
    assert(e.configure(c, now));
    for (unsigned i = 0; i < 3; ++i) {
        e.observe(m, pcm.data(), kFrames, 10, now + i + 1); e.drain(r);
    }
    assert(r.spectrum.sequence && r.spectrum.io == 213 && r.tracks[0].io == 213);
    const auto blocks = r.tracks[0].blocks;
    ++m.uid; e.observe(m, pcm.data(), kFrames, 10, now + 4); --m.uid;
    ++m.session; e.observe(m, pcm.data(), kFrames, 10, now + 5); --m.session;
    e.drain(r); assert(r.tracks[0].blocks == blocks);
    // A second eligible track makes the source ambiguous, even on another I/O.
    ++m.id; m.io = 221;
    e.observe(m, pcm.data(), kFrames, 10, now + 6); e.drain(r);
    assert(r.spectrum.sequence == 0);
    // Once the old track is stale, only new-window data may be displayed.
    for (unsigned i = 0; i < 3; ++i) {
        e.observe(m, pcm.data(), kFrames, 10, now + 300000000 + i); e.drain(r);
    }
    assert(r.spectrum.sequence && r.spectrum.io == 221);
    m.io = 229; e.observe(m, pcm.data(), kFrames, 10, now + 300000004); e.drain(r);
    assert(r.spectrum.sequence == 0 && r.tracks[1].io == 229);
    assert(r.tracks[1].discontinuities >= 1);
    // Explicit diagnostic pinning still rejects other outputs.
    c.io = 93; assert(e.configure(c, now)); r = Report{};
    e.observe(m, pcm.data(), kFrames, 10, now + 1); e.drain(r);
    assert(r.tracks[0].blocks == 0);
    m.io = 93; m.type = 1;
    e.observe(m, pcm.data(), kFrames, 10, now + 2); e.drain(r);
    assert(r.tracks[0].blocks == 0);
}
unsigned allocations = 0;
Engine* allocate_test_engine() { ++allocations; return new Engine; }
Engine* fail_allocation() { ++allocations; return nullptr; }
void lazy_lifetime() {
    LazyEngine lazy;
    assert(lazy.get() == nullptr && allocations == 0);
    assert(lazy.ensure(fail_allocation) == nullptr);
    assert(lazy.get() == nullptr && allocations == 1);
    // Readers see either null or a fully constructed engine. They never create
    // tables or rings themselves, including after capture has stopped.
    std::atomic<bool> done{false};
    std::thread reader([&] {
        while (!done.load()) {
            if (Engine* e = lazy.get()) assert(e->deadline.load() == 0);
        }
    });
    Engine* first = lazy.ensure(allocate_test_engine);
    assert(first && allocations == 2);
    first->stop();
    assert(lazy.ensure(allocate_test_engine) == first && allocations == 2);
    done.store(true); reader.join();
    // Test-only destruction after all readers join. Production retains storage.
    delete first;
}
void converted_stage_and_mono() {
    Engine e; Report r; auto m = meta(); assert(e.configure(command(),now));
    std::array<float,2048*2> pcm{};
    constexpr double pi = 3.14159265358979323846;
    // L=750Hz/.2, R=2343.75Hz/.8. Converted must follow L, not mean power.
    for (size_t i=0; i<2048; ++i) {
        pcm[2*i] = float(.2*std::sin(2*pi*64*i/4096));
        pcm[2*i+1] = float(.8*std::sin(2*pi*200*i/4096));
    }
    m.rate = 48000;
    e.observe(m,pcm.data(),2048,10,now+1); e.drain(r);
    m.stage = 1;
    e.observe(m,pcm.data(),2048,10,now+2); e.drain(r);
    assert(r.spectrum.sequence == 0 && r.tracks[0].discontinuities == 1);
    e.observe(m,pcm.data(),2048,10,now+3); e.drain(r);
    assert((r.reserved & 1) == 1 && r.spectrum.channels == 1);
    assert(std::abs(r.spectrum.rms - .2/std::sqrt(2)) < .00001);
    assert(r.spectrum.bands[3] > 100*r.spectrum.bands[4]);
    const auto blocks = r.tracks[0].blocks;
    m.stage = 0;
    e.observe(m,pcm.data(),2048,10,now+4); e.drain(r);
    assert(r.tracks[0].blocks == blocks); // no duplicate raw window
    e.observe(m,pcm.data(),2048,10,now+200000004); e.drain(r);
    assert((r.reserved & 1) == 0 && r.spectrum.sequence == 0);
    e.observe(m,pcm.data(),2048,10,now+200000005); e.drain(r);
    assert((r.reserved&128) && r.spectrum.channels == 1 && r.spectrum.bands[3] > 100*r.spectrum.bands[4]);
    // A new lease discards stage preference and old PCM.
    assert(e.configure(command(),now)); r = Report{};
    e.observe(m,pcm.data(),2048,20,now+6); e.drain(r);
    assert(r.tracks[0].blocks == 1 && (r.reserved & 1) == 0);
    m.stage = 1; m.channels = 1; m.mask = 1; m.frame_size = 4;
    e.observe(m,pcm.data(),2048,20,now+7); e.counters(r);
    assert(r.rejected == 1);
}
void spatial_common_and_engine() {
    using Z=std::complex<double>;
    assert(std::abs(SpatialRenderer::common(Z{1,0},Z{1,0})-Z{1,0})<1e-12);
    assert(std::abs(SpatialRenderer::common(Z{1,0},Z{.5,0})-Z{.5,0})<1e-12);
    assert(std::abs(SpatialRenderer::common(Z{1,0},Z{-1,0}))==0);
    assert(std::abs(SpatialRenderer::common(Z{},Z{1,0}))==0);
    const auto phase=SpatialRenderer::common(Z{1,0},Z{0,1});
    assert(std::abs(phase-Z{.25,.25})<1e-12);
    Engine e;Report r;auto m=meta();m.rate=48000;m.channels=12;m.mask=0x2d63f;m.frame_size=48;
    std::vector<float> pcm(kFrames*12);
    auto fill=[&](unsigned kind) {
        std::fill(pcm.begin(),pcm.end(),0);
        for(size_t n=0;n<kFrames;++n) {
            const float v=float(.1*std::sin(6.2831853071795864769*750*n/48000));
            if(kind==0) {pcm[n*12]=v;pcm[n*12+1]=v;}
            if(kind==1) {pcm[n*12]=v;pcm[n*12+1]=-v;}
            if(kind==2) pcm[n*12+4]=v; // Android BL must map to Apple rear6.
        }
    };
    const double gains[]{1.00521963794,1.39731503232,.722134866541};
    for(unsigned kind=0;kind<3;++kind) {
        assert(e.configure(command(),now));r=Report{};fill(kind);
        for(unsigned n=0;n<24;++n) {e.observe(m,pcm.data(),kFrames,10,now+n*10000000+1);e.drain(r);}
        assert((r.reserved&16) && r.spectrum.sequence && r.spectrum.channels==1);
        assert(std::abs(r.spectrum.rms-(.1/std::sqrt(2)*gains[kind]))<.00001);
        assert(r.tracks[0].channels==12 && r.tracks[0].drops==0);
        // Stop clears FFT and every convolution/STFT tail; no replay on resume.
        e.stop();assert(e.clear_signal(&r));assert(r.spectrum.sequence==0);
        assert(e.configure(command(),now));r=Report{};std::fill(pcm.begin(),pcm.end(),0);
        for(unsigned n=0;n<8;++n) {e.observe(m,pcm.data(),kFrames,10,now+n*10000000+1);e.drain(r);}
        assert(r.spectrum.sequence && r.spectrum.rms==0);
    }
    // Same count with another positional layout must fall back and invalidate
    // the mono window; channel count alone never enables the speaker model.
    m.mask=0xfff;e.observe(m,pcm.data(),kFrames,10,now+80000001);e.drain(r);
    assert(!(r.reserved&16) && r.spectrum.sequence==0);
    e.observe(m,pcm.data(),kFrames,10,now+90000001);e.drain(r);
    assert(r.spectrum.channels==12 && r.spectrum.sequence);
}
void ordinary_mono_lifecycle() {
    auto e=std::make_unique<Engine>();Report r;auto m=meta();
    std::array<float,kFrames*2> pcm{};
    for(unsigned n=0;n<kFrames;++n){pcm[n*2]=.25f;pcm[n*2+1]=.75f;}
    for(unsigned rate:{44100u,48000u}) {
        m.rate=rate;m.app_gain=0xa000c000u;
        assert(e->configure(command(),now));r=Report{};
        for(unsigned n=0;n<5;++n){e->observe(m,pcm.data(),kFrames,10,now+n*10000000+1);e->drain(r);}
        assert((r.reserved&128) && r.spectrum.rate==48000 && r.spectrum.channels==1);
        assert(std::abs(r.spectrum.rms-.125)<1e-6);
        assert(r.tracks[0].channels==2 && r.tracks[0].rate==rate);
        e->stop();assert(e->clear_signal(&r));assert(!r.spectrum.sequence && !(r.reserved&241));
        assert(e->configure(command(),now));r=Report{};
        for(unsigned n=0;n<kFrames;++n)pcm[n*2]=0;
        for(unsigned n=0;n<5;++n){e->observe(m,pcm.data(),kFrames,10,now+n*10000000+1);e->drain(r);}
        assert(r.spectrum.sequence && r.spectrum.rms==0);
        for(unsigned n=0;n<kFrames;++n)pcm[n*2]=.25f;
    }
    m.rate=96000;m.app_gain=kUnityAppGain;assert(e->configure(command(),now));r=Report{};
    for(unsigned n=0;n<2;++n){e->observe(m,pcm.data(),kFrames,10,now+n*10000000+1);e->drain(r);}
    assert(!(r.reserved&128) && r.spectrum.channels==2 && r.spectrum.rate==96000);
    m.rate=44100;for(auto& v:pcm)v=0;
    e->observe(m,pcm.data(),kFrames,10,now+30000001);e->drain(r);assert(!r.spectrum.sequence);
    for(unsigned n=0;n<3;++n){e->observe(m,pcm.data(),kFrames,10,now+40000001+n*10000000);e->drain(r);}
    assert((r.reserved&128) && r.spectrum.sequence && r.spectrum.rms==0);
}
void app_gain_before_display_fft() {
    assert(app_gain_left(kUnityAppGain)==1 && app_gain_right(kUnityAppGain)==1);
    assert(app_gain_channel(0)==0 && app_gain_channel(0xffff)<2);
    assert(std::abs(app_gain_channel(0xba81)-.457061768f)<1e-8);
    for(unsigned code=0;code<65536;++code) {
        const unsigned exponent=code>>13,mantissa=code&8191;
        const float oracle=std::ldexp(float(exponent ? 8192|mantissa : mantissa<<1)/16384,int(exponent)-6);
        assert(app_gain_channel(uint16_t(code))==oracle);
    }
    auto e=std::make_unique<Engine>();Report r;auto m=meta();m.rate=48000;
    std::array<float,kFrames*2> pcm{};std::array<int16_t,kFrames*2> shorts{};
    for(size_t n=0;n<kFrames;++n) {
        const double wave=std::sin(6.2831853071795864769*750*n/48000);
        pcm[n*2]=float(.25*wave);pcm[n*2+1]=float(.5*wave);
        shorts[n*2]=int16_t(std::lround(pcm[n*2]*32768));shorts[n*2+1]=int16_t(std::lround(pcm[n*2+1]*32768));
    }
    for(uint32_t format:{1u,5u})for(uint32_t stage:{0u,1u}) {
        assert(e->configure(command(),now));r=Report{};
        m.format=format;m.frame_size=format==1?4:8;m.stage=stage;
        // App L=.5,R=.25 produce equal .125 amplitudes from unequal sources.
        m.app_gain=0xa000c000u;
        for(unsigned n=0;n<4;++n) {e->observe(m,format==1?static_cast<void*>(shorts.data()):pcm.data(),kFrames,10,now+n*10000000+1);e->drain(r);}
        assert(r.spectrum.sequence && (r.reserved&32) && !(r.reserved&64));
        assert(r.spectrum.app_gain==m.app_gain);
        assert(std::abs(r.spectrum.rms-.125/std::sqrt(2))<.00001);
        // A gain change must be reflected without a format change or history freeze.
        m.app_gain=kUnityAppGain;
        for(unsigned n=4;n<8;++n) {e->observe(m,format==1?static_cast<void*>(shorts.data()):pcm.data(),kFrames,10,now+n*10000000+1);e->drain(r);}
        const double expected=.25/std::sqrt(2);
        assert(std::abs(r.spectrum.rms-expected)<.00001);
        const auto blocks=r.tracks[0].blocks;
        m.app_gain=0;e->observe(m,pcm.data(),kFrames,10,now+80000001);e->drain(r);e->counters(r);
        assert(r.app_muted==1 && r.tracks[0].blocks==blocks);
    }
    // Spatial reference gets the same common App gain once, after rendering.
    m=meta();m.rate=48000;m.channels=12;m.mask=0x2d63f;m.frame_size=48;
    std::vector<float> spatial(kFrames*12);
    for(size_t n=0;n<kFrames;++n)spatial[n*12]=spatial[n*12+1]=float(.1*std::sin(6.2831853071795864769*750*n/48000));
    for(unsigned mode=0;mode<3;++mode) {
        assert(e->configure(command(),now));r=Report{};
        m.app_gain=mode==2?0xa000c000u:0xc000c000u;m.app_gain_known=mode!=1;
        for(unsigned n=0;n<24;++n) {e->observe(m,spatial.data(),kFrames,10,now+n*10000000+1);e->drain(r);}
        assert((r.reserved&16) && (r.reserved&(mode==0?32:64)));
        assert(std::abs(r.spectrum.rms-.1/std::sqrt(2)*1.00521963794*(mode==0?.5:1))<.00001);
        e->stop();assert(e->clear_signal(&r));assert(!(r.reserved&241) && r.spectrum.sequence==0);
    }
}
}
int main() { ordinary_mono_lifecycle(); app_gain_before_display_fft(); spatial_common_and_engine(); converted_stage_and_mono(); gates_and_energy(); drops_and_format(); quiescence(); protocol(); fft_signal(); renewal(); identities(); lazy_lifetime(); changing_outputs(); apple_bands_and_cadence(); apple_accelerate_reference(); multichannel_energy(); multichannel_copy_guards(); }
