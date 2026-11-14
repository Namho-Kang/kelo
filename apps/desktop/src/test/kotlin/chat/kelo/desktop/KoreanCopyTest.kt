package chat.kelo.desktop

import chat.kelo.app.res.*
import org.junit.Assert.assertEquals
import org.junit.Test

/** Korean sentences that put a number in them (screen check). */
class KoreanCopyTest {
    private val ko = Words.of("ko")

    /** "1.0.3가" is wrong (삼 ends in a consonant) and a version cannot take the particle either way: the version follows a colon. */
    @Test fun theUpdateLineGivesTheVersionAfterAColonNotWithAParticle() {
        assertEquals("새 버전이 있어요: 1.0.3 · 0.3 MB", ko.get(Res.string.update_available, "1.0.3", "0.3 MB"))
    }
}
