# Copyright 2026 wxxsfxyzm
# SPDX-License-Identifier: Apache-2.0
"""Producer identity must follow runtime code, not local research or host tests."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'native/spectrum-tap-zn/build.py'
SPEC = importlib.util.spec_from_file_location('native_build', SCRIPT)
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)


class SourceIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ['engine.cpp', 'engine.h', 'build.py', 'CMakeLists.txt', 'exports.map']:
            (self.root / name).write_text(name)
        self.original = BUILD.source_hash(self.root)

    def test_runtime_and_build_changes_invalidate_pair(self):
        for name in ['engine.cpp', 'engine.h', 'build.py', 'CMakeLists.txt', 'exports.map']:
            with self.subTest(name=name):
                path = self.root / name
                path.write_text('changed')
                self.assertNotEqual(self.original, BUILD.source_hash(self.root))
                path.write_text(name)

    def test_host_tests_docs_and_control_do_not_invalidate_pair(self):
        for name in ['engine_test.cpp', 'layout_test.cpp', 'layout_audit.cpp', 'README.md',
                     'control.py', 'AGENTS.md', 'module.prop']:
            (self.root / name).write_text('not producer code')
        (self.root / '__pycache__').mkdir()
        self.assertEqual(self.original, BUILD.source_hash(self.root))

    def test_new_runtime_source_and_renames_invalidate_pair(self):
        path = self.root / 'extra.cpp'
        path.write_text('engine.cpp')
        self.assertNotEqual(self.original, BUILD.source_hash(self.root))
        path.unlink()
        (self.root / 'engine.cpp').rename(self.root / 'renamed.cpp')
        self.assertNotEqual(self.original, BUILD.source_hash(self.root))


if __name__ == '__main__':
    unittest.main()
