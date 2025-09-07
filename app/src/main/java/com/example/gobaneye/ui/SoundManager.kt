package com.example.gobaneye.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

class SoundManager(private val context: Context) {
  private val soundPool: SoundPool
  private var stoneId: Int = -1
  private var captureId: Int = -1
  private var passId: Int = -1
  private var resignId: Int = -1

  init {
    val attrs = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_GAME)
      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
      .build()
    soundPool = SoundPool.Builder()
      .setMaxStreams(3)
      .setAudioAttributes(attrs)
      .build()

    stoneId = loadOptional("stone")
    captureId = loadOptional("capture")
    passId = loadOptional("pass")
    resignId = loadOptional("resign")
  }

  private fun loadOptional(rawName: String): Int {
    val resId = context.resources.getIdentifier(rawName, "raw", context.packageName)
    return if (resId != 0) soundPool.load(context, resId, 1) else -1
  }

  fun playStone() {
    if (stoneId > 0) soundPool.play(stoneId, 1f, 1f, 1, 0, 1f)
  }

  fun playCapture() {
    if (captureId > 0) soundPool.play(captureId, 1f, 1f, 1, 0, 1f)
  }

  fun playPass() {
    if (passId > 0) soundPool.play(passId, 1f, 1f, 1, 0, 1f)
  }

  fun playResign() {
    if (resignId > 0) soundPool.play(resignId, 1f, 1f, 1, 0, 1f)
  }

  fun release() {
    soundPool.release()
  }
}
