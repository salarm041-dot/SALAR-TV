from pathlib import Path

p = Path("project")  # The extracted app project is located through PROJECT_DIR below.
import os
target = Path(os.environ["PROJECT_DIR"]) / "app/src/main/java/com/salar/tv/MainActivity.kt"
s = target.read_text(encoding="utf-8")

replacements = [
("""    DisposableEffect(Unit) { onDispose { liveSubtitleEngine?.stop(); player.release(); aiAudioCapture.clear() } }
""",
"""    DisposableEffect(Unit) { onDispose { liveSubtitleEngine?.stop(); player.playWhenReady = false; player.stop(); player.clearMediaItems(); player.release(); aiAudioCapture.clear() } }
    LaunchedEffect(selected?.url) {
        if (selected == null) {
            liveSubtitleEngine?.stop()
            liveSubtitleEngine = null
            aiAudioCapture.stop()
            player.playWhenReady = false
            player.pause()
            player.stop()
            player.clearMediaItems()
        }
    }
"""),
("""                    TextButton(onClick = { selected = null }) { Icon(Icons.Default.ArrowBack, null); Text("بازگشت") }
""",
"""                    TextButton(onClick = {
                        liveSubtitleEngine?.stop()
                        liveSubtitleEngine = null
                        aiAudioCapture.stop()
                        player.playWhenReady = false
                        player.pause()
                        player.stop()
                        player.clearMediaItems()
                        selected = null
                    }) { Icon(Icons.Default.ArrowBack, null); Text("بازگشت") }
"""),
("""                    LaunchedEffect(selected!!.url, useExternalPlayer) {
                        val storedAddress = selected!!.url
""",
"""                    LaunchedEffect(selected!!.url, useExternalPlayer) {
                        // Always tear down the previous item before switching source/player mode.
                        liveSubtitleEngine?.stop()
                        liveSubtitleEngine = null
                        aiAudioCapture.stop()
                        player.playWhenReady = false
                        player.pause()
                        player.stop()
                        player.clearMediaItems()
                        val storedAddress = selected!!.url
"""),
("""                        if (officialWebPage) {
                            player.pause()
""",
"""                        if (officialWebPage) {
                            player.playWhenReady = false
                            player.pause()
                            player.stop()
                            player.clearMediaItems()
"""),
("""                        } else if (useExternalPlayer && !isXtreamSource) {
                            player.pause()
""",
"""                        } else if (useExternalPlayer && !isXtreamSource) {
                            player.playWhenReady = false
                            player.pause()
                            player.stop()
                            player.clearMediaItems()
"""),
("""fun AboutPage() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
""",
"""fun AboutPage() {
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "نامشخص"
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
"""),
("""        Text("نسخه 1.3.0", color = MutedText)
""",
"""        Text("نسخه $versionName", color = MutedText)
""")
]

for old, new in replacements:
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"Safe patch aborted: expected one match, found {count}: {old[:100]!r}")
    s = s.replace(old, new, 1)

target.write_text(s, encoding="utf-8")
print("Applied playback lifecycle cleanup and dynamic About version.")
