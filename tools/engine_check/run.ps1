$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '../..')
try {
    $cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
    function Jar($group, $artifact, $version) {
        $path = Join-Path $cache "$group/$artifact/$version"
        $result = Get-ChildItem -LiteralPath $path -Recurse -Filter "$artifact-$version.jar" | Select-Object -First 1
        if (!$result) { throw "Missing cached jar: $path" }
        return $result.FullName
    }
    $std = Jar 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.4.21'
    $cp = @(
        (Jar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '2.4.21'),
        $std,
        (Jar 'org.jetbrains.kotlin' 'kotlin-script-runtime' '2.4.21'),
        (Jar 'org.jetbrains.kotlin' 'kotlin-reflect' '2.4.10'),
        (Jar 'org.jetbrains.kotlin' 'kotlin-daemon-embeddable' '2.4.21'),
        (Jar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.11.0'),
        (Jar 'org.jetbrains' 'annotations' '13.0')
    ) -join ';'
    $java = Join-Path $env:JAVA_HOME 'bin/java.exe'
    $android = 'D:/Program/Android/Sdk/platforms/android-37.0/android.jar'
    $out = Join-Path (Get-Location) 'build/engine-check'
    New-Item -ItemType Directory -Path $out -Force | Out-Null
    & $java -cp $cp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -classpath "$std;$android" -d $out -jvm-target 17 app/src/main/java/com/kongye/keyboard/Lexicon.kt app/src/main/java/com/kongye/keyboard/PinyinCorrection.kt app/src/main/java/com/kongye/keyboard/HandwritingCandidates.kt tools/engine_check/Check.kt
    if ($LASTEXITCODE -ne 0) { throw 'Kotlin compilation failed' }
    & $java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp "$out;$std" CheckKt app/src/main/assets/lexicon.txt
    if ($LASTEXITCODE -ne 0) { throw 'Engine checks failed' }
} finally { Pop-Location }
