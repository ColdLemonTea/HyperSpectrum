# Copyright 2026 wxxsfxyzm
# SPDX-License-Identifier: Apache-2.0

# LSPosed instantiates this entry by its name in META-INF/xposed/java_init.list.
-keep class io.github.wxxsfxyzm.hyperspectrum.SpectrumModule {
    public <init>();
}

-repackageclasses
-allowaccessmodification
-overloadaggressively
