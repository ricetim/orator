package com.orator.feature.audiobookshelf.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AbsUrlTest {
    @Test fun `serverId strips trailing slash and lowercases host`() {
        assertEquals("https://abs.example.com", AbsUrl.serverId("https://ABS.Example.com/"))
    }

    @Test fun `serverId keeps explicit port`() {
        assertEquals("http://host:13378", AbsUrl.serverId("http://host:13378/"))
    }

    @Test fun `endpoint joins base and path without doubling slash`() {
        assertEquals("https://abs.example.com/login", AbsUrl.endpoint("https://abs.example.com/", "login"))
    }
}
