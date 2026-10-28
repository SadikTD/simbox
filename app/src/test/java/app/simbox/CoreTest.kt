package app.simbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    @Test fun cryptoRoundTrip() {
        val k = rand(32)
        assertEquals("আপনার কোড 1234", Crypto.dec(Crypto.enc("আপনার কোড 1234", k), k))
        assertNull(Crypto.dec(Crypto.enc("secret", k), rand(32)))
    }

    @Test fun otpDetection() {
        assertEquals("482913", otp("Your verification code is 482913. Do not share it."))
        assertEquals("5521", otp("5521 is your bKash OTP. Valid for 2 minutes."))
        assertEquals("7788", otp("Use code 7788 to log in. Call 01712345678 for help."))
        assertNull(otp("Recharge of Tk 50 to 01712345678 successful."))
    }

    @Test fun classification() {
        assertEquals(Kind("otp"), classify("bKash", "Your bKash verification code is 482913"))
        assertEquals(Kind("money", "+৳12,500.00"), classify("BRAC BANK", "Tk 12,500.00 credited to A/C **4471 on 28-Oct. Avl Bal Tk 58,210.40"))
        assertEquals(Kind("money", "-৳500"), classify("bKash", "Send Money Tk 500 to 01711000111 successful. Balance Tk 1,200"))
        assertEquals(Kind("promo"), classify("Cirkle 30D", "cirkle-এ আজ ৩৫জিবি+৩০০মিনিট - ৳৩৯৭ ৩০দিন/*4*397#"))
        assertEquals(Kind("personal"), classify("+8801712345678", "Bhai, call me when you're free"))
        assertEquals(Kind("other"), classify("Foodpanda", "Your rider is arriving with order #FP-2231"))
    }

    @Test fun helpers() {
        assertEquals("1712345678", digits10("+880 1712-345678"))
        assertNull(digits10("Robi"))
        assertTrue(newer("1.1.0", "1.0.0"))
        assertTrue(newer("1.10", "1.9.9"))
        assertFalse(newer("1.1.0", "1.1.0"))
    }
}
