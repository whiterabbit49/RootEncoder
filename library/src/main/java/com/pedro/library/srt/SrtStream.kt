/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.library.srt

import android.content.Context
import android.media.MediaCodec
import android.os.Build
import androidx.annotation.RequiresApi
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.sources.video.VideoSource
import com.pedro.library.base.StreamBase
import com.pedro.library.util.streamclient.SrtStreamClient
import com.pedro.library.util.streamclient.StreamClientListener
import com.pedro.srt.srt.SrtClient
import java.nio.ByteBuffer

/**
 * Created by pedro on 8/9/23.
 *
 * If you use VideoManager.Source.SCREEN/AudioManager.Source.INTERNAL. Call
 * changeVideoSourceScreen/changeAudioSourceInternal is necessary to start it.
 */

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class SrtStream(
    context: Context, connectChecker: ConnectChecker, videoSource: VideoSource,
    audioSource: AudioSource
): StreamBase(context, videoSource, audioSource) {

  private val srtClient = SrtClient(connectChecker)
  private val streamClientListener = object: StreamClientListener {
    override fun onRequestKeyframe() {
      requestKeyframe()
    }
  }
  override fun getStreamClient(): SrtStreamClient = SrtStreamClient(srtClient, streamClientListener)

  constructor(context: Context, connectChecker: ConnectChecker):
      this(context, connectChecker, Camera2Source(context), MicrophoneSource())

  override fun setVideoCodecImp(codec: VideoCodec) {
    srtClient.setVideoCodec(codec)
  }

  override fun setAudioCodecImp(codec: AudioCodec) {
    srtClient.setAudioCodec(codec)
  }

  override fun onAudioInfoImp(sampleRate: Int, isStereo: Boolean) {
    srtClient.setAudioInfo(sampleRate, isStereo)
  }

  override fun startStreamImp(endPoint: String) {
    srtClient.connect(endPoint)
  }

  override fun stopStreamImp() {
    srtClient.disconnect()
  }

  override fun onVideoInfoImp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
    srtClient.setVideoInfo(sps, pps, vps)
  }

  override fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
      // trackcast latency SEI: on each IDR, prepend a user_data_unregistered SEI NAL carrying
      // the wall-clock capture time so the MediaMTX RTSP receiver (trackcast-latency-rx) can
      // measure ingest latency. Inserted before the first VCL NAL (SPS/PPS -> SEI -> VCL order).
      // Recording taps getVideoData before this, so local recordings stay free of the SEI.
      // Format mirrors crates/common/src/sei.rs (UUID b7c1f2a0-4e83-4d67-9b01-c3a5de120001 + f64 BE seconds).
      val sei = TrackcastSei.buildAvcc(System.currentTimeMillis())
      val (out, newInfo) = injectSeiBeforeVcl(videoBuffer, info, sei)
      srtClient.sendVideo(out, newInfo)
    } else {
      srtClient.sendVideo(videoBuffer, info)
    }
  }

  /**
   * Insert [sei] (an AVCC NAL: 4-byte BE length + body) into the access unit in [videoBuffer]
   * just before the first VCL NAL (nal_type 1 or 5), preserving SPS/PPS -> SEI -> VCL order.
   * Returns a fresh (ByteBuffer, BufferInfo) with the updated size. Uses a duplicate() view so
   * the original buffer's position is untouched (it is recycled by the encoder after this call).
   */
  private fun injectSeiBeforeVcl(
    videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo, sei: ByteArray
  ): Pair<ByteBuffer, MediaCodec.BufferInfo> {
    val view = videoBuffer.duplicate()
    view.position(info.offset)
    val frame = ByteArray(info.size)
    view.get(frame)
    var insertAt = 0
    var pos = 0
    while (pos + 4 <= frame.size) {
      val b0 = frame[pos].toInt() and 0xFF
      val b1 = frame[pos + 1].toInt() and 0xFF
      val b2 = frame[pos + 2].toInt() and 0xFF
      val b3 = frame[pos + 3].toInt() and 0xFF
      val len = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
      if (len <= 0 || pos + 4 + len > frame.size) break  // malformed; fall back to prepend
      val nalType = frame[pos + 4].toInt() and 0x1F
      if (nalType == 1 || nalType == 5) { insertAt = pos; break }
      pos += 4 + len
    }
    val out = ByteBuffer.allocate(sei.size + frame.size)
    out.put(frame, 0, insertAt)
    out.put(sei)
    out.put(frame, insertAt, frame.size - insertAt)
    out.flip()
    val newInfo = MediaCodec.BufferInfo()
    newInfo.set(0, out.limit(), info.presentationTimeUs, info.flags)
    return out to newInfo
  }

  /** Builds the trackcast latency SEI NAL in AVCC form. Mirrors crates/common/src/sei.rs. */
  private object TrackcastSei {
    private val UUID = byteArrayOf(
      0xb7.toByte(), 0xc1.toByte(), 0xf2.toByte(), 0xa0.toByte(),
      0x4e.toByte(), 0x83.toByte(), 0x4d.toByte(), 0x67.toByte(),
      0x9b.toByte(), 0x01.toByte(), 0xc3.toByte(), 0xa5.toByte(),
      0xde.toByte(), 0x12.toByte(), 0x00.toByte(), 0x01.toByte()
    )

    /** AVCC NAL: 4-byte BE length + SEI body (06 05 18 UUID(16) f64-BE-seconds(8) 80) = 32 bytes. */
    fun buildAvcc(epochMs: Long): ByteArray {
      val out = ByteArray(32)
      val bb = ByteBuffer.wrap(out)
      bb.putInt(28)                                    // AVCC length prefix
      bb.put(0x06.toByte())                            // nal_unit_type = 6 (SEI)
      bb.put(0x05.toByte())                            // sei_message_type = 5 (user_data_unregistered)
      bb.put(0x18.toByte())                            // payload size = 24
      bb.put(UUID)                                     // 16-byte trackcast UUID
      bb.putDouble(epochMs.toDouble() / 1000.0)        // f64 wall-clock seconds, big-endian
      bb.put(0x80.toByte())                            // RBSP trailing bits
      return out
    }
  }

  override fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    srtClient.sendAudio(audioBuffer, info)
  }
}