package com.pedro.whip

import com.pedro.whip.webrtc.Candidate
import com.pedro.whip.webrtc.CandidateType
import com.pedro.whip.webrtc.isPublicUnicastIp
import com.pedro.whip.webrtc.selectRemoteCandidate
import org.junit.Assert.*
import org.junit.Test

/**
 * trackcast: MediaMTX behind NAT lists candidates loopback → private (pod
 * IP) → public. Talking to [0] sends STUN into a black hole; the selector
 * must prefer the publicly routable address.
 */
class IceCandidateSelectorTest {

  private fun hostCandidate(ip: String, port: Int) =
    Candidate(CandidateType.LOCAL, 1, 1, ip, port, null, null)

  private val mediamtxOrder = listOf(
    hostCandidate("127.0.0.1", 8010),
    hostCandidate("10.0.0.175", 8010),
    hostCandidate("59.12.54.140", 8010),
  )

  @Test
  fun `public candidate wins over loopback and rfc1918`() {
    val selected = selectRemoteCandidate(mediamtxOrder)
    assertEquals("59.12.54.140", selected?.getRealHost())
    assertEquals(8010, selected?.getRealPort())
  }

  @Test
  fun `lan-only list keeps old first-non-loopback behavior`() {
    val lan = listOf(hostCandidate("127.0.0.1", 8010), hostCandidate("192.168.1.20", 8010))
    assertEquals("192.168.1.20", selectRemoteCandidate(lan)?.getRealHost())
  }

  @Test
  fun `empty list selects nothing`() {
    assertNull(selectRemoteCandidate(emptyList()))
  }

  @Test
  fun `private ranges are not public`() {
    assertFalse("10.0.0.175".isPublicUnicastIp())
    assertFalse("172.16.0.9".isPublicUnicastIp())
    assertFalse("172.31.255.1".isPublicUnicastIp())
    assertFalse("192.168.1.20".isPublicUnicastIp())
    assertFalse("127.0.0.1".isPublicUnicastIp())
    assertFalse("169.254.9.9".isPublicUnicastIp())
    assertFalse("fe80::1".isPublicUnicastIp())
    assertFalse("::1".isPublicUnicastIp())
    assertTrue("59.12.54.140".isPublicUnicastIp())
    assertTrue("8.8.8.8".isPublicUnicastIp())
    assertTrue("2001:4860:4860::8888".isPublicUnicastIp())
  }
}
