package com.bidstay.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

public final class Money {
    private Money() {}

    private static final BigDecimal FIFTY = BigDecimal.valueOf(50);

    /** ₹1,100 (Indian digit grouping, no decimals). */
    public static String inr(BigDecimal v) {
        NumberFormat f = NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN"));
        return "₹" + f.format(v.setScale(0, RoundingMode.HALF_UP));
    }

    public static BigDecimal roundUpTo50(BigDecimal v) {
        return v.divide(FIFTY, 0, RoundingMode.CEILING).multiply(FIFTY);
    }
}
