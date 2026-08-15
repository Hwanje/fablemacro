package com.fablemacro.app.input

import android.view.KeyEvent
import com.fablemacro.app.MacroAccessibilityService

/**
 * 키 입력 전송.
 *
 * 안드로이드는 일반 앱이 다른 앱으로 키 이벤트를 주입하는 것을 막아 두었다
 * (INJECT_EVENTS는 시스템 서명 권한). 그래서 아래 두 경로만 실제로 동작한다.
 *
 *  1. 루팅된 기기 — 셸로 `input keyevent`를 실행하면 시스템 전체에 전달된다.
 *     게임처럼 입력창이 없는 앱에 키를 보내려면 이 방법뿐이다.
 *  2. 포커스된 입력창 — 접근성으로 글자를 넣는다. 루팅 없이 되지만 텍스트 필드에 한한다.
 *
 * 둘 다 안 되면 false를 돌려주어 스텝이 «실패»로 처리되고, 분기로 대응할 수 있다.
 */
object KeyInput {

    /** "p", "ENTER", "7" 같은 입력을 KeyEvent 코드로 바꾼다 */
    fun keyCodeOf(input: String): Int {
        val s = input.trim()
        if (s.isEmpty()) return KeyEvent.KEYCODE_UNKNOWN

        if (s.length == 1) {
            val c = s[0]
            when (c) {
                in 'a'..'z' -> return KeyEvent.KEYCODE_A + (c - 'a')
                in 'A'..'Z' -> return KeyEvent.KEYCODE_A + (c - 'A')
                in '0'..'9' -> return KeyEvent.KEYCODE_0 + (c - '0')
            }
        }

        val named = s.uppercase().replace(' ', '_').replace('-', '_')
        val alias = when (named) {
            "ESC" -> "ESCAPE"
            "RETURN" -> "ENTER"
            "PGUP" -> "PAGE_UP"
            "PGDN" -> "PAGE_DOWN"
            "DEL" -> "FORWARD_DEL"
            else -> named
        }
        KeyEvent.keyCodeFromString("KEYCODE_$alias")
            .takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
            ?.let { return it }
        return KeyEvent.keyCodeFromString(alias)
    }

    /** 사람이 읽을 수 있는 이름 (리스트 요약용) */
    fun labelOf(input: String): String {
        val code = keyCodeOf(input)
        return if (code == KeyEvent.KEYCODE_UNKNOWN) "?" else input.trim()
    }

    fun isKnown(input: String): Boolean = keyCodeOf(input) != KeyEvent.KEYCODE_UNKNOWN

    /**
     * 키를 보낸다. 시스템 전체 전달을 먼저 시도하고, 안 되면 포커스된 입력창에 글자를 넣는다.
     */
    fun send(service: MacroAccessibilityService?, key: String): Boolean {
        val code = keyCodeOf(key)
        if (code != KeyEvent.KEYCODE_UNKNOWN && shellKeyEvent(code)) return true

        val printable = key.trim().singleOrNull()?.takeIf { !it.isISOControl() }
        if (printable != null && service?.appendTextToFocused(printable.toString()) == true) {
            return true
        }
        return false
    }

    /** 셸로 키 이벤트 전송 — 루팅된 기기에서만 성공한다 */
    private fun shellKeyEvent(code: Int): Boolean {
        for (cmd in arrayOf(
            arrayOf("su", "-c", "input keyevent $code"),
            arrayOf("input", "keyevent", "$code"),
        )) {
            val ok = runCatching {
                val p = Runtime.getRuntime().exec(cmd)
                val finished = p.waitFor()
                finished == 0
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }
}
