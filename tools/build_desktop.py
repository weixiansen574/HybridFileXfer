#!/usr/bin/env python3
"""Compile, test and package the desktop app using an installed JDK."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import platform
import shutil
import stat
import struct
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / ".build"
FLATLAF_VERSION = "3.7.2"
FLATLAF_SHA256 = "917aff3963c88d797d0fd9b9ccbd70f7681c101df9d11c59e2bc7a3a6c0fabf4"


def flatlaf_dependency():
    name = "flatlaf-" + FLATLAF_VERSION + ".jar"
    cached = ROOT / ".tools" / "dependencies" / name
    if cached.exists():
        data = cached.read_bytes()
    else:
        url = "https://repo.maven.apache.org/maven2/com/formdev/flatlaf/" + FLATLAF_VERSION + "/" + name
        print("Downloading pinned UI dependency:", url, flush=True)
        with urllib.request.urlopen(url, timeout=30) as response:
            data = response.read(5 * 1024 * 1024 + 1)
    if len(data) > 5 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != FLATLAF_SHA256:
        raise RuntimeError("FlatLaf dependency checksum mismatch: " + str(cached))
    if not cached.exists():
        cached.parent.mkdir(parents=True, exist_ok=True)
        cached.write_bytes(data)
    return cached


def bundle_flatlaf(dependency, classes):
    # Keep the standalone JAR self-contained, including native resources,
    # Java 9+ implementations, and the dependency's original Apache license.
    with zipfile.ZipFile(dependency) as archive:
        for entry in archive.infolist():
            if entry.is_dir() or entry.filename in ("META-INF/MANIFEST.MF", "module-info.class"):
                continue
            target = classes / entry.filename
            if not target.resolve().is_relative_to(classes.resolve()):
                raise RuntimeError("Invalid dependency archive path")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))
    shutil.copy2(ROOT / "THIRD_PARTY_NOTICES.txt", classes / "META-INF" / "THIRD_PARTY_NOTICES.txt")
    shutil.copy2(ROOT / "LICENSE.txt", classes / "META-INF" / "LICENSE-GPL-3.0.txt")
    shutil.copytree(ROOT / "licenses", classes / "META-INF" / "licenses", dirs_exist_ok=True)
    shutil.copy2(ROOT / "CHANGES.md", classes / "META-INF" / "CHANGES.md")


def package_icon(classes):
    icon_classes = BUILD / "icon-classes"
    reset_directory(icon_classes)
    compile_java([ROOT / "tools/java/top/weixiansen574/hybridfilexfer/desktop/DesktopIconExporter.java"],
                 icon_classes, classes)
    icons = BUILD / "icons"
    reset_directory(icons)
    run([java_tool("java"), "-Djava.awt.headless=true", "-cp",
         os.pathsep.join([str(classes), str(icon_classes)]),
         "top.weixiansen574.hybridfilexfer.desktop.DesktopIconExporter", icons], timeout=20)
    png = icons / "HybridFileXfer-256.png"
    if sys.platform == "win32":
        return icons / "HybridFileXfer.ico"
    if sys.platform == "darwin":
        chunks = b""
        for kind, size in ((b"ic08", 256), (b"ic09", 512)):
            data = (icons / ("HybridFileXfer-" + str(size) + ".png")).read_bytes()
            chunks += kind + struct.pack(">I", len(data) + 8) + data
        icon = icons / "HybridFileXfer.icns"
        icon.write_bytes(b"icns" + struct.pack(">I", len(chunks) + 8) + chunks)
        return icon
    return png


def java_tool(name):
    suffix = ".exe" if os.name == "nt" else ""
    if os.environ.get("JAVA_HOME"):
        candidate = Path(os.environ["JAVA_HOME"]) / "bin" / (name + suffix)
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(name)
    if not found:
        raise RuntimeError("JDK tool not found: " + name)
    return found


def run(command, **kwargs):
    print("+", " ".join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), cwd=ROOT, check=True, **kwargs)


def reset_directory(path):
    resolved = path.resolve()
    boundary = BUILD.resolve()
    if resolved == boundary or not resolved.is_relative_to(boundary) or not boundary.is_relative_to(ROOT.resolve()):
        raise RuntimeError("Refusing to remove a directory outside the build workspace")
    if path.exists():
        def remove_readonly(function, failed_path, error):
            # Windows jpackage may mark its generated launcher read-only.
            # Only clear that attribute inside this already-validated build tree.
            if (os.name == "nt" and isinstance(error[1], PermissionError)
                    and not os.stat(failed_path).st_mode & stat.S_IWRITE):
                os.chmod(failed_path, stat.S_IWRITE)
                function(failed_path)
            else:
                raise error[1]
        shutil.rmtree(path, onerror=remove_readonly)
    path.mkdir(parents=True)


def compile_java(sources, destination, classpath=None):
    destination.mkdir(parents=True, exist_ok=True)
    args = ["--release", "8", "-encoding", "UTF-8", "-d", str(destination)]
    if classpath:
        args += ["-cp", str(classpath)]
    args += [str(path) for path in sources]
    arg_file = BUILD / (destination.name + ".args")
    arg_file.write_text("\n".join('"' + str(arg).replace("\\", "/").replace('"', '\\"') + '"'
                                  for arg in args), encoding="utf-8")
    run([java_tool("javac"), "@" + str(arg_file)])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--test", action="store_true")
    parser.add_argument("--package", action="store_true")
    parser.add_argument("--source-repository", default="weixiansen574/HybridFileXfer",
                        help="GitHub owner/repository containing the exact build source")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.source_repository):
        parser.error("--source-repository must be a GitHub owner/repository")
    source = ROOT / "HybridFileXfer-PC" / "src"
    classes = BUILD / "classes"
    reset_directory(classes)
    dependency = flatlaf_dependency()
    compile_java(sorted(source.rglob("*.java")), classes, dependency)
    bundle_flatlaf(dependency, classes)
    for resource in source.rglob("*"):
        if resource.is_file() and resource.suffix in (".properties", ".png", ".ico"):
            target = classes / resource.relative_to(source)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(resource, target)
    # Use the author's exact original image for the window and native package icons.
    original_logo = ROOT / "HybridFileXfer-Android/app/src/main/res/drawable/logo.png"
    logo_target = classes / "top/weixiansen574/hybridfilexfer/desktop/logo.png"
    logo_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(original_logo, logo_target)
    jar_dir = BUILD / "jar"
    jar_dir.mkdir(parents=True, exist_ok=True)
    jar_path = jar_dir / "HybridFileXfer.jar"
    source_revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT,
                                     text=True, stdout=subprocess.PIPE, check=True).stdout.strip()
    revision = source_revision[:12]
    dirty = bool(subprocess.run(["git", "status", "--porcelain"], cwd=ROOT,
                                text=True, stdout=subprocess.PIPE, check=True).stdout.strip())
    source_notice = ("Source: https://github.com/" + args.source_repository + "/tree/" + source_revision + "\n"
                     "Build contains local uncommitted changes: " + str(dirty) + "\n")
    (classes / "META-INF" / "SOURCE.txt").write_text(source_notice, encoding="utf-8")
    manifest = BUILD / "MANIFEST.MF"
    manifest.write_text("Manifest-Version: 1.0\n"
                        "Main-Class: top.weixiansen574.hybridfilexfer.Main\n"
                        "Multi-Release: true\n"
                        "Implementation-Version: 4.0.1-preview+" + revision + ("-dirty" if dirty else "") + "\n\n",
                        encoding="utf-8")
    run([java_tool("jar"), "cfm", jar_path, manifest, "-C", classes, "."])
    run([java_tool("java"), "-jar", jar_path, "--version"], timeout=20)
    run([java_tool("java"), "-jar", jar_path, "--help"], timeout=20)
    if args.test:
        tests = sorted((ROOT / "tests" / "java").rglob("*.java"))
        if not tests:
            raise RuntimeError("No Java regression tests found")
        test_classes = BUILD / "test-classes"
        reset_directory(test_classes)
        compile_java(tests, test_classes, classes)
        classpath = os.pathsep.join([str(classes), str(test_classes)])
        reports = BUILD / "test-results"
        reports.mkdir(parents=True, exist_ok=True)
        executed = 0
        for path in tests:
            text = path.read_text(encoding="utf-8")
            if not re.search(r"public\s+static\s+void\s+main\s*\(", text):
                continue
            package = re.search(r"package\s+([\w.]+)\s*;", text)
            name = (package.group(1) + "." if package else "") + path.stem
            print("Test:", name, flush=True)
            result = subprocess.run([java_tool("java"), "-ea", "-Djava.awt.headless=true",
                                     "-cp", classpath, name], cwd=ROOT,
                                    text=True, encoding="utf-8", errors="replace",
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    timeout=180)
            (reports / (name + ".txt")).write_text(result.stdout, encoding="utf-8")
            print(result.stdout, flush=True)
            if result.returncode:
                raise RuntimeError("Regression test failed: " + name)
            executed += 1
        if not executed:
            raise RuntimeError("No runnable regression tests found")
        android = ROOT / "HybridFileXfer-Android/app/src/main/java"
        for prefix in ("top/weixiansen574/hybridfilexfer/core",
                       "top/weixiansen574/hybridfilexfer/security", "top/weixiansen574/nio"):
            for pc in (source / prefix).rglob("*.java"):
                peer = android / pc.relative_to(source)
                if not peer.exists() or pc.read_text(encoding="utf-8") != peer.read_text(encoding="utf-8"):
                    raise RuntimeError("PC/Android shared source differs: " + str(pc.relative_to(source)))
    distributions = BUILD / "distributions"
    distributions.mkdir(parents=True, exist_ok=True)
    shutil.copy2(jar_path, distributions / jar_path.name)
    if args.package:
        image_dir = BUILD / "app"
        reset_directory(image_dir)
        icon = package_icon(classes)
        run([java_tool("jpackage"), "--type", "app-image", "--name", "HybridFileXfer",
             "--input", jar_dir, "--main-jar", jar_path.name, "--dest", image_dir,
             "--app-version", "4.0.1", "--vendor", "HybridFileXfer contributors",
             "--icon", icon,
             "--java-options", "-Dfile.encoding=UTF-8"])
        executable = image_dir / "HybridFileXfer"
        if sys.platform == "win32":
            executable /= "HybridFileXfer.exe"
            # A console-subsystem executable would reintroduce the black CMD window.
            pe = executable.read_bytes()
            header = struct.unpack_from("<I", pe, 0x3c)[0]
            if pe[header:header+4] != b"PE\0\0" or struct.unpack_from("<H", pe, header+24+68)[0] != 2:
                raise RuntimeError("Packaged Windows launcher is not a GUI executable")
            from verify_windows_icon import verify_windows_icon
            for frame in verify_windows_icon(executable, icon):
                print("Verified native EXE icon: {size}px SHA256={sha256}".format(**frame), flush=True)
        elif sys.platform == "darwin":
            executable = image_dir / "HybridFileXfer.app/Contents/MacOS/HybridFileXfer"
        else:
            executable /= "bin/HybridFileXfer"
        run([executable, "--version"], timeout=30)
        documentation = image_dir / "Documentation"
        documentation.mkdir()
        (documentation / "SOURCE.txt").write_text(source_notice, encoding="utf-8")
        for path in (ROOT / "LICENSE.txt", ROOT / "THIRD_PARTY_NOTICES.txt",
                     ROOT / "CHANGES.md", ROOT / "README.md", ROOT / "HybridFileXfer-PC/DESKTOP.md"):
            if path.is_file():
                target = documentation / path.relative_to(ROOT)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, target)
        shutil.copytree(ROOT / "docs", documentation / "docs")
        shutil.copytree(ROOT / "licenses", documentation / "licenses")
        system = {"win32": "windows", "darwin": "macos"}.get(sys.platform, "linux")
        archive = distributions / ("HybridFileXfer-4.0.1-" + system + "-" + platform.machine().lower() + ".zip")
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as output:
            for path in image_dir.rglob("*"):
                if path.is_file():
                    output.write(path, path.relative_to(image_dir))
        print("Packaged:", archive)


if __name__ == "__main__":
    main()
