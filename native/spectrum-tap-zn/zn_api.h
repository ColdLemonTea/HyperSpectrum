// SPDX-License-Identifier: Apache-2.0
// ABI declarations for Zygisk Next 1.5.2, API 4. No hook engine is bundled.
// Reference: https://github.com/LSPosed/ZygiskNext/blob/v1.5.2/zygisk_next_api.h
#pragma once
#include <cstddef>
struct ZnSymbolResolver;
struct ZygiskNextRuntime;
struct ZygiskNextAPI {
    int (*pltHook)(void*, const char*, void*, void**);
    int (*inlineHook)(void*, void*, void**);
    int (*inlineUnhook)(void*);
    ZnSymbolResolver* (*newSymbolResolver)(const char*, void*);
    void (*freeSymbolResolver)(ZnSymbolResolver*);
    void* (*getBaseAddress)(ZnSymbolResolver*);
    void* (*symbolLookup)(ZnSymbolResolver*, const char*, bool, size_t*);
    void (*forEachSymbols)(ZnSymbolResolver*, bool (*)(const char*, void*, size_t, void*), void*);
    int (*connectCompanion)(void*);
    const ZygiskNextRuntime* (*getRuntime)();
};
struct ZygiskNextModule {
    int target_api_version;
    void (*onModuleLoaded)(void*, const ZygiskNextAPI*);
};
struct ZygiskNextCompanionModule {
    int target_api_version;
    void (*onCompanionLoaded)();
    void (*onModuleConnected)(int);
};
