// SPDX-License-Identifier: Apache-2.0
#include "engine.h"
#include "converted_registry.h"
#include "converted_path.h"
#include "lazy_engine.h"
#include "io.h"
#include "zn_api.h"
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <pthread.h>
#include <sys/random.h>
#include <sys/syscall.h>
#include <sys/mman.h>
#include <new>
#include <atomic>

namespace {
using namespace tap;
struct Buffer { const void* raw; size_t frames; };
static_assert(sizeof(Buffer) == 16 && offsetof(Buffer, frames) == 8);
using Intercept = void (*)(void*, const Buffer*);
Intercept original = nullptr;
// The library is also loaded into a root companion. Never construct the PCM
// rings/FFT tables in ELF constructors, the loader callback or that companion.
LazyEngine active_engine;
ZygiskNextAPI zn{};
void* module_handle = nullptr;
Health health = Health::Starting;
uint64_t instance = 0;

template <typename T> T field(const void* object, size_t offset) {
    T value;
    std::memcpy(&value, static_cast<const unsigned char*>(object) + offset, sizeof(T));
    return value;
}
Metadata metadata(void* track) {
    Metadata m{};
    m.app_gain_known = false;
    m.uid = field<uint32_t>(track, 0x1c4); m.session = field<uint32_t>(track, 0x1c0);
    m.id = field<uint32_t>(track, 0x1f0); m.io = field<uint32_t>(track, 0x1fc);
    m.state = __atomic_load_n(reinterpret_cast<const uint32_t*>(
        static_cast<const unsigned char*>(track) + 0x68), __ATOMIC_RELAXED);
    m.usage = field<uint32_t>(track, 0x7c); m.stream = field<uint32_t>(track, 0x5f4);
    m.type = field<uint32_t>(track, 0x1f8); m.format = field<uint32_t>(track, 0x18c);
    m.channels = field<uint32_t>(track, 0x194); m.mask = field<uint32_t>(track, 0x190);
    m.frame_size = field<uint64_t>(track, 0x1b0);
    const uint32_t flags = field<uint32_t>(track, 0x734);
    // No compressed/direct/MMAP observation, including PCM direct paths.
    if (flags & 0x4011u) m.type = UINT32_MAX;
    const auto* proxy = field<const unsigned char*>(track, 0x728);
    if (proxy) {
        const auto* cblk = field<const unsigned char*>(proxy, 0x10);
        if (cblk) {
            m.rate = __atomic_load_n(reinterpret_cast<const uint32_t*>(cblk + 0x14), __ATOMIC_RELAXED);
            m.app_gain = __atomic_load_n(reinterpret_cast<const uint32_t*>(cblk + 0x10), __ATOMIC_RELAXED);
            m.app_gain_known = true;
            m.app_muted = m.app_gain == 0;
        }
    }
    return m;
}
void intercepted(void* track, const Buffer* buffer) {
    Engine* current = active_engine.get();
    if (!current) { original(track, buffer); return; }
    Engine& engine = *current;
    const auto expires = engine.deadline.load(std::memory_order_acquire);
    const auto start = expires ? monotonic_ns() : 0;
    if (expires && start < expires) {
        const auto m = metadata(track);
        engine.observe(m, buffer->raw, buffer->frames, uint32_t(syscall(SYS_gettid)), start);
    }
    original(track, buffer);
}

struct Image { uintptr_t bias = 0; const ElfW(Phdr)* phdr = nullptr; size_t count = 0;
    const char* path = kLibrary; };
int find_image(dl_phdr_info* info, size_t, void* arg) {
    if (std::strcmp(info->dlpi_name, static_cast<Image*>(arg)->path) != 0) return 0;
    *static_cast<Image*>(arg) = {info->dlpi_addr, info->dlpi_phdr, info->dlpi_phnum, static_cast<Image*>(arg)->path};
    return 1;
}
bool mapped(const Image& image, uint64_t offset, uint64_t size, uint32_t flags) {
    for (size_t i = 0; i < image.count; ++i) {
        const auto& p = image.phdr[i];
        if (p.p_type == PT_LOAD && (p.p_flags & flags) == flags && offset >= p.p_vaddr &&
            offset - p.p_vaddr <= p.p_memsz && size <= p.p_memsz - (offset - p.p_vaddr)) return true;
    }
    return false;
}
bool known_build(const Image& image, const unsigned char (&expected)[16]) {
    // Build ID is 16 bytes on this ROM, not an assumed 20-byte SHA-1.
    for (size_t i = 0; i < image.count; ++i) {
        const auto& p = image.phdr[i];
        if (p.p_type != PT_NOTE || p.p_filesz > 1048576 || !mapped(image, p.p_vaddr, p.p_filesz, PF_R)) continue;
        const auto* bytes = reinterpret_cast<const unsigned char*>(image.bias + p.p_vaddr);
        for (size_t off = 0; off + sizeof(Elf64_Nhdr) <= p.p_filesz;) {
            Elf64_Nhdr n{}; std::memcpy(&n, bytes + off, sizeof(n)); off += sizeof(n);
            const uint64_t name_size = (uint64_t(n.n_namesz) + 3) & ~uint64_t(3);
            const uint64_t desc_size = (uint64_t(n.n_descsz) + 3) & ~uint64_t(3);
            if (name_size > p.p_filesz - off || desc_size > p.p_filesz - off - name_size) return false;
            if (n.n_type == NT_GNU_BUILD_ID && n.n_namesz == 4 && n.n_descsz == sizeof(expected) &&
                std::memcmp(bytes + off, "GNU", 4) == 0 &&
                std::memcmp(bytes + off + name_size, expected, sizeof(expected)) == 0) return true;
            off += size_t(name_size + desc_size);
        }
    }
    return false;
}
bool install() {
    Image image; dl_iterate_phdr(find_image, &image);
    constexpr unsigned char build[]{0xc4,0xa7,0x5d,0x8f,0xf6,0xdb,0x95,0x9b,0x8e,0xcd,
                                    0xd2,0xe4,0xf2,0xd5,0x08,0xf8};
    constexpr unsigned char entry[]{0x3f,0x23,0x03,0xd5,0xff,0x03,0x03,0xd1,
                                    0xfd,0x7b,0x06,0xa9,0xfc,0x6f,0x07,0xa9};
    if (!image.bias || !known_build(image, build) || !mapped(image, kOffset, sizeof(entry), PF_R | PF_X)) return false;
    void* target = reinterpret_cast<void*>(image.bias + kOffset);
    if (std::memcmp(target, entry, sizeof(entry)) != 0) return false;
    ZnSymbolResolver* resolver = zn.newSymbolResolver(kLibrary, reinterpret_cast<void*>(image.bias));
    if (!resolver) return false;
    void* symbol = zn.symbolLookup(resolver, kSymbol, false, nullptr);
    zn.freeSymbolResolver(resolver);
    if (symbol != target) return false;
    // This ROM enforces BTI. An inline patch replacing PACIASP at this entry
    // faults when its PLT's BR x17 arrives. Keep all original text/PAC intact
    // and redirect only this library's single reviewed JUMP_SLOT instead.
    constexpr uint64_t got_offset = 0x2ce268;
    if (!mapped(image, got_offset, sizeof(void*), PF_R)) return false;
    auto* got = reinterpret_cast<void* const*>(image.bias + got_offset);
    if (*got != target) return false;
    health = Health::HookFailed;
    original = reinterpret_cast<Intercept>(target);
    void* previous = nullptr;
    if (zn.pltHook(reinterpret_cast<void*>(image.bias), kSymbol,
                   reinterpret_cast<void*>(intercepted), &previous) != 0) return false;
    if (previous != target || *got != reinterpret_cast<void*>(intercepted) ||
        std::memcmp(target, entry, sizeof(entry)) != 0) {
        // Initialization only, before main. Restore the validated original
        // binding if the framework did not fulfill the exact patch contract.
        zn.pltHook(reinterpret_cast<void*>(image.bias), kSymbol, target, nullptr);
        return false;
    }
    return true;
}

// Optional, exact-ROM converted tap. All three hooks keep original text intact.
Image processing{0,nullptr,0,"/system/lib64/libaudioprocessing.so"};
Image audiohal{0,nullptr,0,"/system/lib64/libaudiohal@aidl.so"};
Image flinger;
ConvertedRegistry converted_buffers;
std::atomic<bool> converted_ready{false};
bool converted_attempted = false; // control worker only
// Report bits: stage1, hook ready2, provider registered4, commit matched8.
std::atomic<uint32_t> converted_evidence{0};
using DownmixCtor = void (*)(void*, uint32_t, uint32_t, uint32_t, int32_t,
                            uint32_t, uint32_t, int32_t, size_t);
using Free = void (*)(void*);
using Copy = void* (*)(void*, const void*, size_t);
DownmixCtor original_ctor = nullptr;
Free original_free = nullptr;
Copy original_copy = nullptr;

void downmix_constructed(void* self, uint32_t input, uint32_t output, uint32_t format,
                        int32_t process, uint32_t device, uint32_t rate, int32_t session, size_t frames) {
    original_ctor(self, input, output, format, process, device, rate, session, frames);
    if (!converted_ready.load(std::memory_order_acquire) || output != 3 ||
        (input & 0xc0000000u) || __builtin_popcount(input) < 3 ||
        __builtin_popcount(input) > int(kMaxChannels) || (format != 1 && format != 5) ||
        session <= 0 || rate < 8000 || rate > 192000 || frames != 256 ||
        field<uintptr_t>(self, 0) != processing.bias + 0xb9250 ||
        !field<uintptr_t>(self, 0x50)) return;
    const auto destination = field<uintptr_t>(self, 0x38);
    const auto effect = field<const void*>(self, 0x70);
    const auto stride = format == 5 ? 8u : 4u;
    if (!destination || !effect || field<uintptr_t>(effect, 0) != audiohal.bias + 0x174728 ||
        field<size_t>(self, 0x18) != stride || field<size_t>(self, 0x60) != stride ||
        field<size_t>(self, 0x30) != frames || field<size_t>(effect, 0x10) != frames * stride) return;
    if (converted_buffers.add({reinterpret_cast<uintptr_t>(self), destination,
        field<uintptr_t>(effect, 0x30), frames * stride, uint32_t(session), rate, format}))
        converted_evidence.fetch_or(4, std::memory_order_relaxed);
}
void buffer_freed(void* pointer) {
    converted_buffers.retire(reinterpret_cast<uintptr_t>(pointer));
    original_free(pointer);
}
// Follow only the currently executing provider's live upstream chain. A saved
// Track is never dereferenced. Unknown wrappers/virtual bases fail closed.
void* copied_effect_buffer(void* destination, const void* source, size_t bytes) {
    const auto caller = reinterpret_cast<uintptr_t>(__builtin_extract_return_addr(__builtin_return_address(0)));
    // commit and copyFrames tail-call; only CopyBufferProvider's BLR return
    // address survives. update() has another return address and reversed buffers.
    void* result = original_copy(destination, source, bytes);
    Engine* engine = active_engine.get();
    if (!engine || !converted_ready.load(std::memory_order_acquire) ||
        caller != processing.bias + 0x21b1c) return result;
    const auto expires = engine->deadline.load(std::memory_order_acquire);
    if (!expires) return result;
    const auto now = monotonic_ns();
    if (now >= expires) return result;
    ConvertedBuffer b;
    if (!converted_buffers.find(reinterpret_cast<uintptr_t>(destination),
            reinterpret_cast<uintptr_t>(source), bytes, b)) return result;
    const auto* provider = reinterpret_cast<const void*>(b.provider);
    const auto stride = b.format == 5 ? 8u : 4u;
    if (bytes % stride || bytes / stride > 256 ||
        field<uintptr_t>(provider, 0) != processing.bias + 0xb9250 ||
        field<uintptr_t>(provider, 0x38) != b.destination) return result;
    void* track = converted_source_track(provider, processing.bias, flinger.bias,
        [](uintptr_t offset, size_t size) { return mapped(flinger, offset, size, PF_R); });
    if (!track || field<uintptr_t>(track, 0) != flinger.bias + 0x2b1020) return result;
    auto m = metadata(track);
    if (m.session != b.session || m.rate != b.rate || m.channels <= 2) return result;
    // This exact source layout now has an independent pre-gain reference
    // renderer. Keep its raw feed instead of replacing it with Android downmix.
    if(SpatialRenderer::supports(m.channels,m.mask,m.rate))return result;
    m.format = b.format; m.channels = 2; m.mask = 3; m.frame_size = stride; m.stage = 1;
    converted_evidence.fetch_or(8, std::memory_order_relaxed);
    engine->observe(m, destination, bytes / stride, uint32_t(syscall(SYS_gettid)), now);
    return result;
}

bool exact_bytes(const Image& image, uintptr_t offset, const unsigned char* bytes, size_t size) {
    return mapped(image, offset, size, PF_R | PF_X) &&
        std::memcmp(reinterpret_cast<const void*>(image.bias + offset), bytes, size) == 0;
}
bool patch_import(const Image& image, const char* symbol, uintptr_t got_offset,
                  void* target, void* replacement) {
    if (!target || !mapped(image, got_offset, sizeof(void*), PF_R)) return false;
    auto* got = reinterpret_cast<void* const*>(image.bias + got_offset);
    if (*got != target) return false;
    void* previous = nullptr;
    if (zn.pltHook(reinterpret_cast<void*>(image.bias), symbol, replacement, &previous) != 0) return false;
    if (previous == target && *got == replacement) return true;
    zn.pltHook(reinterpret_cast<void*>(image.bias), symbol, target, nullptr);
    return false;
}
void install_converted() {
    if (converted_attempted || health != Health::Ready) return;
    dl_iterate_phdr(find_image, &processing); dl_iterate_phdr(find_image, &audiohal);
    dl_iterate_phdr(find_image, &flinger);
    // audiohal@aidl may be dlopened after audioserver main starts. Retry only
    // absence; a present but incompatible binary/binding is rejected for life.
    if (!processing.bias || !audiohal.bias || !flinger.bias) return;
    converted_attempted = true;
    constexpr unsigned char processing_build[]{0xeb,0xd5,0x0d,0xa1,0xd5,0x99,0x4b,0xce,
        0xfa,0x39,0x8c,0xb6,0xd7,0x9f,0x86,0xd0};
    constexpr unsigned char hal_build[]{0xc0,0xe2,0x9d,0x97,0x1a,0x7b,0x7a,0x29,
        0x6a,0xc3,0xa4,0x0c,0xca,0x64,0x8c,0xfb};
    constexpr unsigned char ctor_entry[]{0x3f,0x23,0x03,0xd5,0xff,0x83,0x02,0xd1,
        0xfd,0x7b,0x04,0xa9,0xfc,0x6f,0x05,0xa9};
    constexpr unsigned char return_site[]{0x00,0x01,0x3f,0xd6,0xe0,0x03,0x1f,0x2a};
    constexpr unsigned char commit_entry[]{0x5f,0x24,0x03,0xd5,0x08,0x00,0x40,0xf9,
        0xc9,0x05,0x00,0xb0,0x29,0xa1,0x1c,0x91};
    constexpr unsigned char commit_tail[]{0xb3,0xd2,0x02,0x14};
    if (!processing.bias || !audiohal.bias || !flinger.bias ||
        !known_build(processing, processing_build) || !known_build(audiohal, hal_build) ||
        !exact_bytes(processing, 0x21f8c, ctor_entry, sizeof(ctor_entry)) ||
        !exact_bytes(processing, 0x21b18, return_site, sizeof(return_site)) ||
        !exact_bytes(audiohal, 0xbb020, commit_entry, sizeof(commit_entry)) ||
        !exact_bytes(audiohal, 0xbb05c, commit_tail, sizeof(commit_tail))) return;
    constexpr const char* ctor_symbol = "_ZN7android23DownmixerBufferProviderC1E20audio_channel_mask_tS1_14audio_format_tijjim";
    ZnSymbolResolver* resolver = zn.newSymbolResolver(processing.path, reinterpret_cast<void*>(processing.bias));
    if (!resolver) return;
    void* ctor = zn.symbolLookup(resolver, ctor_symbol, false, nullptr);
    zn.freeSymbolResolver(resolver);
    if (ctor != reinterpret_cast<void*>(processing.bias + 0x21f8c)) return;
    original_ctor = reinterpret_cast<DownmixCtor>(ctor);
    original_free = reinterpret_cast<Free>(dlsym(RTLD_DEFAULT, "free"));
    original_copy = reinterpret_cast<Copy>(dlsym(RTLD_DEFAULT, "memcpy"));
    // Install retirement first, registration second, observation last. Partial
    // installation stays pass-through and never enables converted capture.
    if (!patch_import(processing, "free", 0xb9d40, reinterpret_cast<void*>(original_free),
            reinterpret_cast<void*>(buffer_freed))) return;
    if (!patch_import(processing, ctor_symbol, 0xb9c90, ctor, reinterpret_cast<void*>(downmix_constructed))) {
        zn.pltHook(reinterpret_cast<void*>(processing.bias), "free", reinterpret_cast<void*>(original_free), nullptr);
        return;
    }
    if (!patch_import(audiohal, "memcpy", 0x176328, reinterpret_cast<void*>(original_copy),
            reinterpret_cast<void*>(copied_effect_buffer))) {
        zn.pltHook(reinterpret_cast<void*>(processing.bias), ctor_symbol, ctor, nullptr);
        zn.pltHook(reinterpret_cast<void*>(processing.bias), "free", reinterpret_cast<void*>(original_free), nullptr);
        return;
    }
    if (!exact_bytes(processing, 0x21f8c, ctor_entry, sizeof(ctor_entry)) ||
        !exact_bytes(audiohal, 0xbb020, commit_entry, sizeof(commit_entry))) return;
    converted_evidence.fetch_or(2, std::memory_order_relaxed);
    converted_ready.store(true, std::memory_order_release);
}

Engine* allocate_engine() {
    // Called only for a validated capture request, never on library load or
    // idle companion connection. Failure leaves the hook as a pass-through.
    void* storage = mmap(nullptr, sizeof(Engine), PROT_READ | PROT_WRITE,
                         MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (storage == MAP_FAILED) return nullptr;
    return new (storage) Engine;
}

void* worker(void*) {
    Engine* engine = nullptr;
    Report report{};
    report.pid = uint32_t(getpid()); report.instance = instance;
    std::memcpy(report.source_build, TAP_SOURCE_BUILD, sizeof(report.source_build));
    report.health = uint32_t(health);
    uint64_t next_report = 0, last_command = 0;
    bool signal_pending = false;
    for (;;) {
        install_converted();
        int fd = zn.connectCompanion(module_handle);
        if (fd < 0) { if (engine) engine->stop(); usleep(1000000); continue; }
        bool connected = true;
        while (connected) {
            if (!converted_attempted) install_converted();
            const auto now = monotonic_ns();
            // A short lease also fails closed if the worker/companion stalls.
            const auto expiry = engine ? engine->deadline.load(std::memory_order_acquire) : 0;
            if (expiry && now >= expiry) engine->stop();
            if (engine && engine->deadline.load()) engine->drain(report);
            else if (engine && signal_pending && engine->clear_signal(&report)) {
                signal_pending = false; report.spectrum = SpectrumFrame{};
            }
            if (now >= next_report) {
                report.reserved = (report.reserved & 241u) | converted_evidence.load(std::memory_order_relaxed);
                report.now_ns = now; ++report.report_sequence;
                if (engine) engine->counters(report);
                report.enabled = now < report.deadline_ns;
                connected = send_value(fd, report);
                next_report = now + (report.enabled ? 25000000 : 500000000);
            }
            if (!connected) break;
            pollfd p{fd, POLLIN, 0};
            const int ready = poll(&p, 1, expiry && now < expiry ? 2 : 200);
            if (ready < 0 && errno == EINTR) continue;
            if (ready < 0 || (p.revents & (POLLHUP | POLLERR | POLLNVAL))) break;
            if (ready > 0 && (p.revents & POLLIN)) {
                Command cmd;
                if (!receive(fd, cmd)) break;
                if (!valid(cmd, instance, last_command, monotonic_ns())) { if (engine) engine->stop(); continue; }
                last_command = cmd.sequence;
                if (cmd.seconds == 0) {
                    if (engine) engine->stop();
                    report.command_sequence = last_command;
                    next_report = 0; continue;
                }
                if (cmd.reserved == 1 && report.client_generation == cmd.client_generation &&
                    engine && engine->renew(cmd, monotonic_ns())) {
                    report.command_sequence = last_command; continue;
                }
                if (health != Health::Ready) continue;
                if (!engine) engine = active_engine.ensure(allocate_engine);
                // FFT table construction can outlast a short request. Recheck
                // freshness before granting a lease; never activate stale work.
                if (!engine) continue;
                if (!valid(cmd, instance, 0, monotonic_ns()) ||
                    !engine->configure(cmd, monotonic_ns())) { engine->stop(); continue; }
                const auto seq = report.report_sequence;
                report = Report{}; report.pid = uint32_t(getpid()); report.instance = instance;
                std::memcpy(report.source_build, TAP_SOURCE_BUILD, sizeof(report.source_build));
                report.health = uint32_t(health); report.report_sequence = seq;
                report.command_sequence = last_command;
                report.client_generation = cmd.client_generation;
                signal_pending = true;
                report.uid = cmd.uid; report.session = cmd.session; report.io = cmd.io;
                next_report = 0;
            }
        }
        if (engine) engine->stop();
        // Clear pending PCM on disconnect after producer quiescence. No replay.
        Command off; if (engine) engine->configure(off, monotonic_ns());
        report = Report{};
        report.pid = uint32_t(getpid()); report.health = uint32_t(health);
        std::memcpy(report.source_build, TAP_SOURCE_BUILD, sizeof(report.source_build));
        if (getrandom(&instance, sizeof(instance), GRND_NONBLOCK) != sizeof(instance) || !instance) return nullptr;
        report.instance = instance;
        last_command = 0; next_report = 0;
        close(fd); usleep(1000000);
    }
}

void loaded(void* handle, const ZygiskNextAPI* api) {
    char exe[256]{}; const ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (n <= 0 || std::strcmp(exe, "/system/bin/audioserver") != 0 || getuid() != 1041 ||
        !exact_file("/proc/self/cmdline", "/system/bin/audioserver") ||
        !exact_file("/proc/self/attr/current", "u:r:audioserver:s0") || !api ||
        !api->pltHook || !api->newSymbolResolver || !api->symbolLookup ||
        !api->freeSymbolResolver || !api->connectCompanion) return;
    zn = *api; module_handle = handle;
    if (getrandom(&instance, sizeof(instance), GRND_NONBLOCK) != sizeof(instance) || !instance) return;
    health = Health::BinaryRejected;
    if (install()) health = Health::Ready;
    pthread_t thread;
    if (pthread_create(&thread, nullptr, worker, nullptr) == 0) pthread_detach(thread);
    // No dlclose/unhook from a live audio process. Upgrade requires a fresh process.
}
}  // namespace
extern "C" {
__attribute__((visibility("default"), used)) ZygiskNextModule zn_module = {4, loaded};
}
