#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build, check and package the independent probe; never installs or restarts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
NDK_VERSION = '30.0.16138531'
CMAKE_VERSION = '4.1.2'


def source_hash(directory=HERE):
    # Documentation, host tests and control scripts are not producer code.
    # Editing them must not invalidate an otherwise identical APK/library pair.
    files = sorted(p for p in directory.iterdir() if p.is_file() and (
        (p.suffix in {'.cpp', '.h'} and p.name not in {'engine_test.cpp', 'layout_test.cpp', 'layout_audit.cpp'}) or
        p.name in {'CMakeLists.txt', 'exports.map', 'build.py'}))
    digest = hashlib.sha256()
    for path in files:
        digest.update(path.name.encode() + b'\0' + path.read_bytes() + b'\0')
    return digest.hexdigest()


def run(*args):
    subprocess.run([str(x) for x in args], check=True, cwd=ROOT)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME') or
                        os.environ.get('ANDROID_SDK_ROOT'), help='Android SDK root')
    parser.add_argument('--output', type=Path, default=ROOT / 'out/native-tap',
                        help='Build directory; use a separate directory for offline review')
    parser.add_argument('--audit-library', type=Path, action='append', default=[],
                        help='Offline audit of an extracted libaudioflinger.so; never loads code')
    args = parser.parse_args()
    if args.sdk is None:
        parser.error('Set ANDROID_HOME or pass --sdk SDK_PATH')
    NDK = args.sdk / 'ndk' / NDK_VERSION
    suffix = '.exe' if os.name == 'nt' else ''
    CMAKE = args.sdk / 'cmake' / CMAKE_VERSION / 'bin' / ('cmake' + suffix)
    NINJA = CMAKE.with_name('ninja' + suffix)
    OUT = args.output.resolve()
    if not (NDK / 'build/cmake/android.toolchain.cmake').is_file():
        parser.error(f'Required installed NDK {NDK_VERSION} is absent under {args.sdk}')
    if not CMAKE.is_file() or not NINJA.is_file():
        parser.error(f'Required installed CMake {CMAKE_VERSION}/Ninja is absent under {args.sdk}')
    prebuilt = NDK / 'toolchains/llvm/prebuilt'
    tool_dirs = [p / 'bin' for p in prebuilt.iterdir() if p.is_dir()]
    if len(tool_dirs) != 1:
        parser.error(f'Expected one installed NDK host toolchain under {prebuilt}')
    tools = tool_dirs[0]
    OUT.mkdir(parents=True, exist_ok=True)
    digest = source_hash()
    run(CMAKE, '-S', HERE, '-B', OUT / 'host', '-G', 'Ninja',
        f'-DCMAKE_MAKE_PROGRAM={NINJA}', '-DTAP_SANITIZE=ON')
    run(CMAKE, '--build', OUT / 'host')
    run(CMAKE.with_name('ctest' + suffix), '--test-dir', OUT / 'host', '--output-on-failure')
    for library in args.audit_library:
        run(OUT / 'host' / ('layout_audit' + suffix), library.resolve())
    run(CMAKE, '-S', HERE, '-B', OUT / 'android', '-G', 'Ninja',
        f'-DCMAKE_MAKE_PROGRAM={NINJA}', f'-DCMAKE_TOOLCHAIN_FILE={NDK}/build/cmake/android.toolchain.cmake',
        '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-36', '-DANDROID_STL=c++_static',
        '-DCMAKE_BUILD_TYPE=Release', f'-DTAP_SOURCE_BUILD={digest}')
    run(CMAKE, '--build', OUT / 'android')
    so = OUT / 'android/libhyperspectrum_tap.so'
    symbols = subprocess.check_output([tools / ('llvm-nm' + suffix), '-D', '--defined-only', so], text=True)
    exported = {line.split()[-1] for line in symbols.splitlines() if line.split()}
    if exported != {'zn_module', 'zn_companion_module'}:
        raise SystemExit(f'Unexpected exports: {exported}')
    all_symbols = subprocess.check_output([tools / ('llvm-nm' + suffix), '--defined-only', so], text=True)
    if '_GLOBAL__sub_I_module.cpp' in all_symbols or '_ZN12_GLOBAL__N_16engineE' in all_symbols:
        raise SystemExit('Audio engine/FFT must not be constructed while the shared library loads')
    dynamic = subprocess.check_output([tools / ('llvm-readelf' + suffix), '-d', so], text=True)
    for line in dynamic.splitlines():
        if '(NEEDED)' in line and not any(f'[{name}]' in line for name in ['libc.so', 'libm.so', 'libdl.so']):
            raise SystemExit(f'Unexpected shared runtime: {line}')
    notes = subprocess.check_output([tools / ('llvm-readelf' + suffix), '-n', so], text=True)
    if 'BTI' not in notes or 'PAC' not in notes:
        raise SystemExit('Branch protection notes missing')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    version = subprocess.check_output(['git', 'rev-list', '--count', 'HEAD'], cwd=ROOT, text=True).strip()
    quarantine = OUT / 'quarantine'
    quarantine.mkdir(exist_ok=True)
    package = quarantine / 'hyperspectrum-tap-zn-coexistence-unvalidated.zip'
    with zipfile.ZipFile(package, 'w', zipfile.ZIP_DEFLATED) as z:
        for name in ['customize.sh', 'zn_modules.txt']:
            z.write(HERE / name, name)
        prop = (HERE / 'module.prop').read_text().replace('versionCode=1\n', f'versionCode={version}\n')
        z.writestr('module.prop', prop)
        z.write(so, 'lib/libhyperspectrum_tap.so')
        for name in ['LICENSE', 'NOTICE']:
            z.write(ROOT / name, name)
    manifest = dict(source_sha256=digest, git_head=commit, version_code=int(version),
                    library_sha256=hashlib.sha256(so.read_bytes()).hexdigest(),
                    zip_sha256=hashlib.sha256(package.read_bytes()).hexdigest(),
                    target='/system/bin/audioserver', api=4, ndk=NDK.name,
                    default_enabled=False, device_activated=False,
                    do_not_deploy=True, validation_status='offline_only_coexistence_unresolved')
    manifest['library_load_audio_engine_constructor'] = False
    (OUT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest, indent=2))
    print(package)


if __name__ == '__main__':
    main()
