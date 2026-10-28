package app.simbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
