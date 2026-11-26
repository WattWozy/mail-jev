$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

mvn -q clean package -DskipTests

Remove-Item -Recurse -Force desktop\build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force desktop\build | Out-Null
Copy-Item target\mailsorter.jar desktop\build\app.jar

$mods = jdeps --ignore-missing-deps --print-module-deps --multi-release 25 target\mailsorter.jar
jlink --add-modules "$mods,java.sql,java.naming" `
      --strip-debug --no-header-files --no-man-pages --compress=zip-6 `
      --output desktop\build\jre
