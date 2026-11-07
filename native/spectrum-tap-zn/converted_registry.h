// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <atomic>
#include <cstddef>
#include <cstdint>
namespace tap {
struct ConvertedBuffer {
    uintptr_t provider = 0, destination = 0, source = 0;
    size_t capacity = 0;
    uint32_t session = 0, rate = 0, format = 0;
};
// Process-lived metadata only. No PCM, audio engine, allocation or saved pointer
// dereference. The mixer owns provider lifetimes; retirement precedes real free.
class ConvertedRegistry {
public:
    static constexpr size_t kEntries = 16;
    bool add(const ConvertedBuffer& b) {
        if (b.provider < 2 || b.destination < 2 || b.source < 2 || !b.capacity ||
            !b.session || b.rate < 8000 || b.rate > 192000 ||
            (b.format != 1 && b.format != 5)) return false;
        for (auto& e : entries_) {
            uintptr_t empty = 0;
            if (!e.destination.compare_exchange_strong(empty, 1, std::memory_order_acq_rel)) continue;
            e.revision.fetch_add(1, std::memory_order_acq_rel);
            e.provider.store(b.provider, std::memory_order_relaxed);
            e.source.store(b.source, std::memory_order_relaxed);
            e.capacity.store(b.capacity, std::memory_order_relaxed);
            e.session.store(b.session, std::memory_order_relaxed);
            e.rate.store(b.rate, std::memory_order_relaxed);
            e.format.store(b.format, std::memory_order_relaxed);
            e.revision.fetch_add(1, std::memory_order_release);
            e.destination.store(b.destination, std::memory_order_release);
            return true;
        }
        return false; // Capacity exhaustion fails back to the raw tap.
    }
    void retire(uintptr_t destination) {
        if (destination < 2) return;
        for (auto& e : entries_) {
            uintptr_t key = destination;
            e.destination.compare_exchange_strong(key, 0, std::memory_order_acq_rel);
        }
    }
    bool find(uintptr_t destination, uintptr_t source, size_t bytes, ConvertedBuffer& b) const {
        if (destination < 2 || source < 2 || !bytes) return false;
        for (const auto& e : entries_) {
            if (e.destination.load(std::memory_order_acquire) != destination) continue;
            const auto revision = e.revision.load(std::memory_order_acquire);
            if (revision & 1) continue;
            ConvertedBuffer candidate{e.provider.load(std::memory_order_relaxed), destination,
                e.source.load(std::memory_order_relaxed), e.capacity.load(std::memory_order_relaxed),
                e.session.load(std::memory_order_relaxed), e.rate.load(std::memory_order_relaxed),
                e.format.load(std::memory_order_relaxed)};
            if (candidate.source == source && bytes <= candidate.capacity &&
                e.destination.load(std::memory_order_acquire) == destination &&
                e.revision.load(std::memory_order_acquire) == revision) {
                b = candidate; return true;
            }
        }
        return false;
    }
private:
    struct Entry {
        std::atomic<uintptr_t> destination{0}, provider{0}, source{0};
        std::atomic<uint64_t> revision{0};
        std::atomic<size_t> capacity{0};
        std::atomic<uint32_t> session{0}, rate{0}, format{0};
    } entries_[kEntries]{};
};
static_assert(std::atomic<uintptr_t>::is_always_lock_free);
} // namespace tap
