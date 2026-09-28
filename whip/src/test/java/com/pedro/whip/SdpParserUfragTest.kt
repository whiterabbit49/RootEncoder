package com.pedro.whip

import com.pedro.whip.webrtc.SdpParser
import org.junit.Assert.*
import org.junit.Test

/**
 * trackcast: regression tests for parsing MediaMTX WHIP answers. MediaMTX
 * 1.20 appends `ufrag <ufrag>` to every candidate (and TCP candidates carry
 * `tcptype <type>` too); the old fixed-index raddr/rport read parsed the
 * ufrag token as an Int and the resulting NumberFormatException aborted the
 * whole answer inside mapNotNull (every WHIP publish failed).
 */
class SdpParserUfragTest {

  private val answer = """
    v=0
    o=- 6289043619455802016 1790623112 IN IP4 0.0.0.0
    s=-
    t=0 0
    a=fingerprint:sha-256 10:24:D2:D5:78:07:EE:CB:A3:64:D8:85:97:4B:05:20:BB:45:F3:3A:3C:3A:6A:6A:F4:DE:1E:96:25:53:D5:E9
    a=group:BUNDLE 0
    m=video 9 UDP/TLS/RTP/SAVPF 99
    c=IN IP4 0.0.0.0
    a=setup:active
    a=mid:0
    a=ice-ufrag:hdyloGVCmdDIdpUo
    a=ice-pwd:WiRaDjqvdsvxFxNGXdArCfcJxHBWxETh
    a=rtcp-mux
    a=recvonly
    a=candidate:2878742611 1 udp 2130706431 127.0.0.1 8010 typ host ufrag hdyloGVCmdDIdpUo
    a=candidate:375738803 1 tcp 1671430143 127.0.0.1 8011 typ host tcptype passive ufrag hdyloGVCmdDIdpUo
    a=candidate:3892993245 1 udp 2130706431 59.12.54.140 8010 typ host
    a=candidate:1596929843 1 tcp 1671430143 59.12.54.140 8011 typ host tcptype passive
    a=end-of-candidates
  """.trimIndent()

  @Test
  fun `mediaMTX ufrag-suffixed candidates parse without throwing`() {
    val info = SdpParser.parseBodyAnswer(answer)
    assertEquals("hdyloGVCmdDIdpUo", info.uFrag)
    assertEquals(4, info.candidates.size)
  }

  @Test
  fun `udp candidate without raddr keeps address-port as local`() {
    val info = SdpParser.parseBodyAnswer(answer)
    val udp = info.candidates.first { it.localAddress == "59.12.54.140" && it.localPort == 8010 }
    assertEquals("59.12.54.140", udp.localAddress)
    assertEquals("59.12.54.140", udp.getRealHost())
    assertEquals(8010, udp.getRealPort())
  }

  @Test
  fun `legacy raddr-rport candidate still maps by key`() {
    val legacy = "v=0\r\na=ice-ufrag:x\r\na=ice-pwd:y\r\na=fingerprint:sha-256 AA\r\n" +
      "a=candidate:1 1 udp 1234 203.0.113.7 5000 typ srflx raddr 192.168.1.2 rport 6000\r\n"
    val info = SdpParser.parseBodyAnswer(legacy)
    assertEquals(1, info.candidates.size)
    val c = info.candidates[0]
    assertEquals("192.168.1.2", c.localAddress)
    assertEquals(6000, c.localPort)
    assertEquals("203.0.113.7", c.publicAddress)
    assertEquals(5000, c.publicPort)
    assertEquals("203.0.113.7", c.getRealHost())
  }

  @Test
  fun `garbage candidate line is skipped not fatal`() {
    val broken = "v=0\r\na=ice-ufrag:x\r\na=ice-pwd:y\r\na=fingerprint:sha-256 AA\r\n" +
      "a=candidate:not-a-candidate-at-all\r\n" +
      "a=candidate:1 1 udp 1234 203.0.113.7 5000 typ srflx\r\n"
    val info = SdpParser.parseBodyAnswer(broken)
    assertEquals(1, info.candidates.size)
  }
}
