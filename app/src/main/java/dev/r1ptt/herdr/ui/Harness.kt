package dev.r1ptt.herdr.ui

import dev.r1ptt.R

/**
 * Which coding tool an agent runs, and how the herd draws it: its brand color, the ink that reads
 * on that color, and its sheep. Color names the harness only; state is always written out.
 */
enum class Harness(
    val label: String,
    val color: Int,
    val ink: Int,
    val sheep: Int,
    val asleep: Int,
    val logo: Int,
) {
    CLAUDE("Claude Code", R.color.herd_claude, R.color.herd_claude_ink, R.drawable.sheep_brand_claude, R.drawable.sheep_brand_claude_sleep, R.drawable.logo_claude),
    CODEX("Codex", R.color.herd_codex, R.color.herd_codex_ink, R.drawable.sheep_brand_codex, R.drawable.sheep_brand_codex_sleep, R.drawable.logo_codex),
    PI("Pi", R.color.herd_pi, R.color.herd_pi_ink, R.drawable.sheep_brand_pi, R.drawable.sheep_brand_pi_sleep, R.drawable.logo_pi),
    OPENCODE("OpenCode", R.color.herd_opencode, R.color.herd_opencode_ink, R.drawable.sheep_brand_opencode, R.drawable.sheep_brand_opencode_sleep, R.drawable.logo_opencode),
    OTHER("Agent", R.color.herd_other, R.color.herd_other_ink, R.drawable.sheep_plain, R.drawable.sheep_plain_sleep, R.drawable.herdr_sheep);

    companion object {
        fun of(kind: String): Harness = when (kind.lowercase()) {
            "claude", "claude-code", "claude_code" -> CLAUDE
            "codex" -> CODEX
            "pi" -> PI
            "opencode" -> OPENCODE
            else -> OTHER
        }
    }
}
