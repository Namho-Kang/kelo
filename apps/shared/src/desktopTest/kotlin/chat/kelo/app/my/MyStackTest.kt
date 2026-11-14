package chat.kelo.app.my

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyStackTest {
    @Test
    fun homeIsNotFullScreenAndASubPageIs() {
        assertFalse(MyStack.isSub(emptyList()))
        assertTrue(MyStack.isSub(MyStack.push(emptyList(), "vault")))
    }

    @Test
    fun backStepsOutOneLevelAtATime() {
        val deep = MyStack.push(MyStack.push(emptyList(), "vault"), "model")
        assertEquals(listOf("vault"), MyStack.pop(deep))
        assertTrue(MyStack.isSub(MyStack.pop(deep)))
        assertFalse(MyStack.isSub(MyStack.pop(MyStack.pop(deep))))
        assertEquals(emptyList<String>(), MyStack.pop(emptyList()))
    }
}
