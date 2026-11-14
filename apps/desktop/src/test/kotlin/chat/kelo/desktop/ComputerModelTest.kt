package chat.kelo.desktop

import chat.kelo.desktop.core.ComputerModel
import kotlin.test.Test
import kotlin.test.assertEquals

class ComputerModelTest {
    @Test fun macModelName() = assertEquals("MacBook Pro", ComputerModel.pick("Mac", null, " MacBook Pro\n", null))
    @Test fun macWithoutAnswer() = assertEquals("Mac", ComputerModel.pick("Mac", null, null, null))
    @Test fun lenovoUsesTheVersion() =
        assertEquals("ThinkPad X1 Carbon Gen 11", ComputerModel.pick("Windows", "LENOVO", "21HM004GKR", "ThinkPad X1 Carbon Gen 11"))
    @Test fun dellUsesTheProduct() = assertEquals("XPS 13 9340", ComputerModel.pick("Windows", "Dell Inc.", "XPS 13 9340", "1.0"))
    @Test fun placeholdersFallBack() =
        assertEquals("Windows PC", ComputerModel.pick("Windows", "System manufacturer", "System Product Name", "To Be Filled By O.E.M."))
    @Test fun linuxFallback() = assertEquals("Linux", ComputerModel.pick("Linux", null, "", null))
}
