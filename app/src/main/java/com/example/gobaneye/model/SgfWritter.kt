package com.example.gobaneye.model

class SgfWriter(
  private val size: Int = 19,
  private val komi: Double? = null
) {
  private val sb = StringBuilder()

  init {
    sb.append("(;GM[1]FF[4]SZ[$size]")
    komi?.let { k ->
      // Pretty-print integer komi
      val s = if (k == k.toInt().toDouble()) "${k.toInt()}" else k.toString()
      sb.append("KM[$s]")
    }
  }

  private fun coord(x: Int, y: Int): String =
    "${'a' + x}${'a' + y}"

  fun addMove(m: Move) {
    if (m.isPass) {
      sb.append(if (m.color == Stone.BLACK) ";B[]" else ";W[]")
    } else {
      val prop = if (m.color == Stone.BLACK) "B" else "W"
      sb.append(";$prop[${coord(m.x, m.y)}]")
    }
  }

  fun finalize(result: String? = null): String {
    result?.let { sb.append(";RE[$it]") }
    sb.append(")")
    return sb.toString()
  }
}
