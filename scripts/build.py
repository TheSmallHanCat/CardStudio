"""Build an isolated source snapshot, including from Windows paths containing Chinese characters."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", default=os.environ.get("JAVA_HOME"), help="JDK 17+ directory")
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("tasks", nargs="*", default=[":app:testDebugUnitTest", ":app:assembleDebug", ":app:lintDebug"])
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.jdk or not args.sdk:
        parser.error("Set JAVA_HOME and ANDROID_HOME, or pass --jdk and --sdk.")
    jdk, sdk = Path(args.jdk).resolve(), Path(args.sdk).resolve()
    java = jdk / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not java.is_file() or not (sdk / "platforms" / "android-36").is_dir():
        parser.error("A JDK 17+ and Android SDK platform 36 are required.")
    env = dict(os.environ, JAVA_HOME=str(jdk), ANDROID_HOME=str(sdk))
    output = root / "dist"
    output.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="cardstudio-build-") as directory:
        work = Path(directory)
        if os.name == "nt" and not str(work).isascii():
            parser.error("Use an ASCII-only TEMP directory on Windows.")
        ignore = shutil.ignore_patterns("build", ".gradle", ".git", "__pycache__")
        for name in ("app", "gradle"):
            shutil.copytree(root / name, work / name, ignore=ignore)
        for name in ("settings.gradle.kts", "build.gradle.kts", "gradle.properties"):
            shutil.copy2(root / name, work / name)
        sdk_property = sdk.as_posix().replace(":", "\\:")
        (work / "local.properties").write_text(f"sdk.dir={sdk_property}\n", encoding="utf-8")
        command = [str(java), "-classpath", str(work / "gradle/wrapper/gradle-wrapper.jar"),
                   "org.gradle.wrapper.GradleWrapperMain", *args.tasks, "--console=plain"]
        result = subprocess.run(command, cwd=work, env=env, check=False)
        for apk in (work / "app/build/outputs/apk").glob("**/*.apk"):
            shutil.copy2(apk, output / f"CardStudio-{apk.parent.name}.apk")
        for source, target in (("app/build/reports", "reports"), ("app/build/test-results", "test-results")):
            folder = work / source
            if folder.exists():
                shutil.copytree(folder, output / target, dirs_exist_ok=True)
        print(f"Artifacts and validation reports: {output}", flush=True)
        return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
