"""Compile the standalone probe against existing debug classes, without network access.

Run after :app:compileDebugKotlin. Prints a temporary workspace with classpath.txt.
The probe uses only synthetic data. It is never packaged in the application.
"""
from pathlib import Path
import os
import subprocess
import tempfile
import zipfile

root = Path(__file__).resolve().parents[2]
work = Path(tempfile.mkdtemp(prefix="pindorama-crypto-review-"))
jdk = root / ".gradle/local-jdk21/usr/lib/jvm/java-21-openjdk-amd64"
classes = root / "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"
coordinates = [
    "org.bouncycastle/bcprov-jdk15to18/1.86",
    "org.jetbrains.kotlin/kotlin-stdlib/2.4.20",
    "com.squareup.okio/okio-jvm/3.18.2",
]
jars = []
for coordinate in coordinates:
    matches = list((cache / coordinate).glob("*/*.jar"))
    assert len(matches) == 1, (coordinate, matches)
    jars.append(matches[0])
engine = work / "engine.jar"
with zipfile.ZipFile(engine, "w") as archive:
    for source in (classes / "eu/kanade/tachiyomi/data/backup/crypto").glob("*.class"):
        archive.write(source, source.relative_to(classes))
classpath = os.pathsep.join(map(str, [work, engine] + jars))
subprocess.run([
    str(jdk / "bin/javac"), "--release", "17", "-encoding", "UTF-8", "-cp", classpath,
    "-d", str(work), str(Path(__file__).with_name("Benchmark.java")),
], check=True)
with zipfile.ZipFile(work / "probe.jar", "w") as archive:
    for source in work.glob("*.class"):
        archive.write(source, source.name)
(work / "classpath.txt").write_text(classpath)
(work / "dependencies.txt").write_text("\n".join(map(str, jars)))
print(work)
