package io.github.lnrdo.mytidal.core

import org.junit.Assert.*
import org.junit.Test

class SignInCallbackTest {
    @Test fun acceptsTheRegisteredNativeCallback() {
        assertTrue(SignInCallback.matches("tidal://login/auth?code=test&state=test", SignInCallback.NATIVE))
        assertFalse(SignInCallback.matches("https://tidal.com/android/login/auth?code=test", SignInCallback.NATIVE))
    }

    @Test fun rejectsDifferentOriginsCredentialsPathsAndFragments() {
        listOf("tidal://attacker/auth?code=test", "tidal://login.attacker.example/auth?code=test", "tidal://login/auth/other?code=test",
            "tidal://user:password@login/auth?code=test", "tidal://login:443/auth?code=test", "tidal://login/auth?code=test#fragment",
            "http://tidal.com/android/login/auth?code=test", "https://tidal.com.attacker.example/android/login/auth?code=test").forEach {
            assertFalse(it, SignInCallback.supported(it))
        }
    }

    @Test fun decodesParametersOnceAndPreservesDuplicatesForRejection() {
        val values = SignInCallback.parameters("tidal://login/auth?code=a%2Bb%26c&state=first&state=second")
        assertEquals(listOf("a+b&c"), values["code"])
        assertEquals(listOf("first", "second"), values["state"])
    }
}
