// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "engine.h"

namespace tap {
// Only the control worker may call ensure(). The audio callback only calls get().
// Published storage must live until process exit: stopping capture does not make
// it safe to free an object an audio callback may already have loaded.
class LazyEngine {
public:
    Engine* get() const { return engine_.load(std::memory_order_acquire); }
    Engine* ensure(Engine* (*allocate)()) {
        Engine* current = get();
        if (!current) {
            current = allocate();
            if (current) engine_.store(current, std::memory_order_release);
        }
        return current;
    }
private:
    std::atomic<Engine*> engine_{nullptr};
};
}  // namespace tap
