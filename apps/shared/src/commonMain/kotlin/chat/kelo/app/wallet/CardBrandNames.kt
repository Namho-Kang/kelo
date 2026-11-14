package chat.kelo.app.wallet

/** Brand names are product names, the same in every language (not translated). */
fun brandShort(b: CardBrand): String = when (b) {
    CardBrand.VISA -> "VISA"
    CardBrand.MASTERCARD -> "MC"
    CardBrand.AMEX -> "AMEX"
    CardBrand.JCB -> "JCB"
    CardBrand.DISCOVER -> "DISC"
    CardBrand.UNIONPAY -> "UP"
    CardBrand.DINERS -> "DC"
    CardBrand.OTHER -> "•••"
}

fun brandName(b: CardBrand): String? = when (b) {
    CardBrand.VISA -> "Visa"
    CardBrand.MASTERCARD -> "Mastercard"
    CardBrand.AMEX -> "American Express"
    CardBrand.JCB -> "JCB"
    CardBrand.DISCOVER -> "Discover"
    CardBrand.UNIONPAY -> "UnionPay"
    CardBrand.DINERS -> "Diners Club"
    CardBrand.OTHER -> null
}
