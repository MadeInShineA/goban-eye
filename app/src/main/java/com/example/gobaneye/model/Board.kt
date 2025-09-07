package com.example.gobaneye.model

enum class Stone { EMPTY, BLACK, WHITE }

data class Move(val color: Stone, val x: Int, val y: Int, val isPass: Boolean = false)

data class PlayResult(
  val ok: Boolean,
  val capturedBlack: Int = 0, // number of BLACK stones removed by this move
  val capturedWhite: Int = 0  // number of WHITE stones removed by this move
)

class Board(private val size: Int = 19) {
  // 0 empty, 1 black, 2 white
  private val cells = IntArray(size * size)
  private fun idx(x: Int, y: Int) = y * size + x
  fun get(x: Int, y: Int): Stone = when (cells[idx(x, y)]) {
    1 -> Stone.BLACK
    2 -> Stone.WHITE
    else -> Stone.EMPTY
  }

  fun size() = size

  // simple ko: forbid immediate recapture at this single point
  private var koPoint: Int? = null

  private inline fun forNeighbors(x: Int, y: Int, f: (nx: Int, ny: Int) -> Unit) {
    if (x > 0) f(x - 1, y)
    if (x < size - 1) f(x + 1, y)
    if (y > 0) f(x, y - 1)
    if (y < size - 1) f(x, y + 1)
  }

  private fun floodGroupAndLiberties(
    sx: Int,
    sy: Int,
    colorVal: Int,
    groupOut: IntArray,
    seen: BooleanArray
  ): Pair<Int, Boolean> {
    var gsz = 0
    var hasLib = false
    val stackX = IntArray(size * size)
    val stackY = IntArray(size * size)
    var top = 0
    stackX[top] = sx; stackY[top] = sy; top++
    seen[idx(sx, sy)] = true
    while (top > 0) {
      top--
      val x = stackX[top]
      val y = stackY[top]
      val i = idx(x, y)
      groupOut[gsz++] = i
      forNeighbors(x, y) { nx, ny ->
        val ni = idx(nx, ny)
        val v = cells[ni]
        if (v == 0) {
          hasLib = true
        } else if (!seen[ni] && v == colorVal) {
          seen[ni] = true
          stackX[top] = nx; stackY[top] = ny; top++
        }
      }
    }
    return gsz to hasLib
  }

  fun play(m: Move): PlayResult {
    if (m.isPass) {
      koPoint = null
      return PlayResult(ok = true)
    }
    val i = idx(m.x, m.y)
    if (cells[i] != 0) return PlayResult(false)
    if (koPoint != null && koPoint == i) return PlayResult(false)

    val my = if (m.color == Stone.BLACK) 1 else 2
    val opp = if (my == 1) 2 else 1

    // place tentatively
    cells[i] = my

    var capturedBlack = 0
    var capturedWhite = 0
    var totalCaptured = 0
    var lastCapturedIndex: Int? = null

    // capture adjacent opponent groups with no liberties
    val seen = BooleanArray(size * size)
    val group = IntArray(size * size)
    forNeighbors(m.x, m.y) { nx, ny ->
      val ni = idx(nx, ny)
      if (cells[ni] == opp && !seen[ni]) {
        val (gsz, hasLib) = floodGroupAndLiberties(nx, ny, opp, group, seen)
        if (!hasLib) {
          // remove captured stones
          for (k in 0 until gsz) {
            val gi = group[k]
            cells[gi] = 0
            lastCapturedIndex = gi
          }
          totalCaptured += gsz
          if (opp == 1) capturedBlack += gsz else capturedWhite += gsz
        }
      }
    }

    // suicide check
    if (totalCaptured == 0) {
      // check liberties of the played group
      java.util.Arrays.fill(seen, false)
      val (gsz, hasLib) = floodGroupAndLiberties(m.x, m.y, my, group, seen)
      if (!hasLib) {
        // illegal (suicide), revert
        cells[i] = 0
        return PlayResult(false)
      }
    }

    // simple ko: only when exactly one stone captured and our group size is 1
    koPoint = if (totalCaptured == 1) {
      // if our stone is a single (no same-color neighbors), mark ko at last captured point
      var singleton = true
      forNeighbors(m.x, m.y) { nx, ny -> if (cells[idx(nx, ny)] == my) singleton = false }
      if (singleton) lastCapturedIndex else null
    } else null

    return PlayResult(true, capturedBlack = capturedBlack, capturedWhite = capturedWhite)
  }

  fun countStones(color: Stone): Int {
    val v = if (color == Stone.BLACK) 1 else 2
    var c = 0
    for (i in cells.indices) if (cells[i] == v) c++
    return c
  }

  /**
   * Territory map after the game:
   * 0 none, 1 black, 2 white, 3 neutral
   */
  fun computeTerritory(): Array<IntArray> {
    val mark = IntArray(size * size) { -1 } // -1 unknown, 0 stone, 1 B terr, 2 W terr, 3 neutral, 4 visited empty
    val out = Array(size) { IntArray(size) { 0 } }
    val seen = BooleanArray(size * size)
    val qx = IntArray(size * size)
    val qy = IntArray(size * size)

    // mark stones as 0
    for (y in 0 until size) for (x in 0 until size) {
      val v = cells[idx(x, y)]
      if (v != 0) mark[idx(x, y)] = 0
    }

    // BFS on empty regions
    for (y in 0 until size) for (x in 0 until size) {
      val i0 = idx(x, y)
      if (cells[i0] != 0 || seen[i0]) continue
      var head = 0;
      var tail = 0
      qx[tail] = x; qy[tail] = y; tail++
      seen[i0] = true

      var borderBlack = false
      var borderWhite = false
      val regionIdxs = IntArray(size * size)
      var rsz = 0

      while (head < tail) {
        val cx = qx[head];
        val cy = qy[head]; head++
        val ci = idx(cx, cy)
        regionIdxs[rsz++] = ci
        forNeighbors(cx, cy) { nx, ny ->
          val ni = idx(nx, ny)
          val v = cells[ni]
          if (v == 0 && !seen[ni]) {
            seen[ni] = true
            qx[tail] = nx; qy[tail] = ny; tail++
          } else if (v == 1) borderBlack = true
          else if (v == 2) borderWhite = true
        }
      }

      val owner = when {
        borderBlack && borderWhite -> 3 // neutral
        borderBlack -> 1
        borderWhite -> 2
        else -> 3
      }
      for (k in 0 until rsz) {
        val ii = regionIdxs[k]
        mark[ii] = owner
      }
    }

    // copy to 2D array
    for (y in 0 until size) for (x in 0 until size) {
      out[y][x] = when (mark[idx(x, y)]) {
        1 -> 1
        2 -> 2
        3 -> 3
        else -> 0
      }
    }
    return out
  }
}
