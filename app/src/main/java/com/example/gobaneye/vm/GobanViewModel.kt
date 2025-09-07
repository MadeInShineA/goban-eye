package com.example.gobaneye.vm

import androidx.lifecycle.ViewModel
import com.example.gobaneye.model.Board
import com.example.gobaneye.model.Move
import com.example.gobaneye.model.PlayResult
import com.example.gobaneye.model.SgfWriter
import com.example.gobaneye.model.Stone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class EndReason { NONE, DOUBLE_PASS, RESIGN }
enum class ScoringRule { CHINESE, JAPANESE }
enum class TerritoryOwner { NONE, BLACK, WHITE, NEUTRAL }

data class Score(
  val rule: ScoringRule,
  val komi: Double,
  val blackStonesOnBoard: Int,
  val whiteStonesOnBoard: Int,
  val blackTerritory: Int,
  val whiteTerritory: Int,
  val prisonersBlack: Int,
  val prisonersWhite: Int,
  val blackTotal: Double,
  val whiteTotal: Double
)

data class LiveScore(
  val score: Score,
  /** 0 none, 1 black, 2 white, 3 neutral */
  val territory: Array<IntArray>
)

class GobanViewModel : ViewModel() {

  data class UI(
    val isPreGame: Boolean,
    val isGameOver: Boolean,
    val isReviewMode: Boolean,
    val reviewIndex: Int,
    val totalMoves: Int,
    val nextToPlay: Stone,
    val size: Int,
    val komi: Double,
    val rule: ScoringRule,
    val prisonersBlack: Int,
    val prisonersWhite: Int,
    val resignedBy: Stone? = null,
    val endReason: EndReason = EndReason.NONE,
    val result: String? = null,
    val stoneAt: (Int, Int) -> Stone,
    val moveNumberAt: (Int, Int) -> Int?,
    val territoryAt: (Int, Int) -> TerritoryOwner,
    val lastMove: Pair<Int, Int>?,
    val score: Score?
  )

  /** Settings */
  private var size = 19
  private var komi = 6.5
  private var rule = ScoringRule.JAPANESE

  /** Game lifecycle */
  private var started = false

  /** Main game record */
  private var mainMoves = mutableListOf<Move>()
  private var currentBoard = Board(size)
  private var nextToPlay = Stone.BLACK
  private var prisonersBlack = 0
  private var prisonersWhite = 0

  /** End state */
  private var endReason: EndReason = EndReason.NONE
  private var resignedBy: Stone? = null
  private var result: String? = null
  private var finalScore: Score? = null
  private var finalTerritoryGrid: Array<IntArray>? = null

  /** Review layer */
  private var isReview = false
  private var reviewBaseIndex = 0
  private var reviewMoves = mutableListOf<Move>()
  private var reviewCursor = 0

  private val _ui = MutableStateFlow(buildUi())
  val ui: StateFlow<UI> = _ui

  /* ---------------- Public API ---------------- */

  fun beginGame(size: Int, komi: Double, rule: ScoringRule) {
    this.size = size
    this.komi = komi
    this.rule = rule
    resetState()
    started = true
    pushUi()
  }

  fun newGame() {
    resetState()
    pushUi()
  }

  data class PlayInfo(val ok: Boolean, val capturedBlack: Int, val capturedWhite: Int)

  fun tryPlay(x: Int, y: Int): PlayInfo {
    if (!started || endReason != EndReason.NONE) return PlayInfo(false, 0, 0)

    if (isReview) {
      val (b, toPlayAtCursor) = boardAndToPlayAtCursor()
      val move = Move(color = toPlayAtCursor, x = x, y = y, isPass = false)
      val res = b.play(move)
      if (!res.ok) return PlayInfo(false, 0, 0)

      val posInReview = (reviewCursor - reviewBaseIndex).coerceAtLeast(0)
      if (posInReview < reviewMoves.size) {
        reviewMoves = reviewMoves.subList(0, posInReview).toMutableList()
      }
      reviewMoves.add(move)
      reviewCursor += 1
      pushUi(light = false)               // <-- full refresh so the very first review stone appears
      return PlayInfo(true, res.capturedBlack, res.capturedWhite)
    } else {
      val move = Move(color = nextToPlay, x = x, y = y, isPass = false)
      val res: PlayResult = currentBoard.play(move)
      if (!res.ok) return PlayInfo(false, 0, 0)

      mainMoves.add(move)
      if (move.color == Stone.WHITE && res.capturedBlack > 0) prisonersWhite += res.capturedBlack
      if (move.color == Stone.BLACK && res.capturedWhite > 0) prisonersBlack += res.capturedWhite
      nextToPlay = opposite(nextToPlay)
      pushUi()
      return PlayInfo(true, res.capturedBlack, res.capturedWhite)
    }
  }

  fun pass() {
    if (!started || endReason != EndReason.NONE) return

    if (isReview) {
      val (_, toPlayAtCursor) = boardAndToPlayAtCursor()
      val move = Move(color = toPlayAtCursor, x = -1, y = -1, isPass = true)
      val posInReview = (reviewCursor - reviewBaseIndex).coerceAtLeast(0)
      if (posInReview < reviewMoves.size) {
        reviewMoves = reviewMoves.subList(0, posInReview).toMutableList()
      }
      reviewMoves.add(move)
      reviewCursor += 1
      pushUi(light = false)               // <-- keep consistent with tryPlay
    } else {
      mainMoves.add(Move(color = nextToPlay, x = -1, y = -1, isPass = true))
      nextToPlay = opposite(nextToPlay)
      val n = mainMoves.size
      if (n >= 2 && mainMoves[n - 1].isPass && mainMoves[n - 2].isPass) {
        endReason = EndReason.DOUBLE_PASS
        computeFinal()
      }
      currentBoard = buildBoardFrom(mainMoves, upTo = mainMoves.size)
      pushUi()
    }
  }

  fun resign(by: Stone) {
    if (!started || endReason != EndReason.NONE) return
    endReason = EndReason.RESIGN
    resignedBy = by
    computeFinal()
    pushUi()
  }

  fun undo() {
    if (!started || endReason != EndReason.NONE) return
    if (isReview) {
      if (reviewCursor > 0) reviewCursor -= 1
      pushUi(light = false)               // <-- ensure board updates immediately
      return
    }
    if (mainMoves.isEmpty()) return
    mainMoves.removeLast()
    currentBoard = buildBoardFrom(mainMoves, upTo = mainMoves.size)
    recomputePrisonersFromRecord()
    nextToPlay = currentToPlayAt(mainMoves.size)
    pushUi()
  }

  fun enterReview(atIndex: Int = mainMoves.size) {
    isReview = true
    reviewBaseIndex = atIndex.coerceIn(0, mainMoves.size)
    reviewMoves.clear()
    reviewCursor = reviewBaseIndex
    pushUi()
  }

  /** Discard review moves and return to the original game. */
  fun exitReview() {
    if (!isReview) return
    isReview = false
    reviewMoves.clear()
    currentBoard = buildBoardFrom(mainMoves, upTo = mainMoves.size)
    recomputePrisonersFromRecord()
    nextToPlay = currentToPlayAt(mainMoves.size)
    pushUi()
  }

  fun prevMove() {
    if (isReview) {
      reviewCursor = (reviewCursor - 1).coerceAtLeast(0); pushUi()
    }
  }

  fun nextMove() {
    if (isReview) {
      val max = reviewBaseIndex + reviewMoves.size; reviewCursor = (reviewCursor + 1).coerceAtMost(max); pushUi()
    }
  }

  fun jumpStart() {
    if (isReview) {
      reviewCursor = 0; pushUi()
    }
  }

  fun jumpEnd() {
    if (isReview) {
      reviewCursor = reviewBaseIndex + reviewMoves.size; pushUi()
    }
  }

  fun stepTo(index: Int) {
    if (isReview) {
      val max = reviewBaseIndex + reviewMoves.size; reviewCursor = index.coerceIn(0, max); pushUi()
    }
  }

  fun exportSgf(): String {
    val writer = SgfWriter(size = size, komi = komi)
    mainMoves.forEach { writer.addMove(it) }
    return writer.finalize(result = result)
  }

  fun computeLiveScore(): LiveScore {
    val b = shownBoard()
    val terr = b.computeTerritory()
    val s = scoreBoard(
      board = b,
      rule = rule,
      komi = komi,
      prisonersBlack = prisonersBlack,
      prisonersWhite = prisonersWhite,
      territory = terr
    )
    return LiveScore(score = s, territory = terr)
  }

  /* ---------------- Internal helpers ---------------- */

  private fun computeFinal() {
    val b = currentBoard
    val terr = b.computeTerritory()
    val s = scoreBoard(b, rule, komi, prisonersBlack, prisonersWhite, terr)
    finalScore = s
    finalTerritoryGrid = terr
    result = when (endReason) {
      EndReason.RESIGN -> if (resignedBy == Stone.BLACK) "W+R" else "B+R"
      EndReason.DOUBLE_PASS -> {
        val diff = s.blackTotal - s.whiteTotal
        when {
          diff > 0 -> "B+${marginStr(diff)}"
          diff < 0 -> "W+${marginStr(-diff)}"
          else -> "Draw"
        }
      }

      else -> null
    }
  }

  private fun scoreBoard(
    board: Board,
    rule: ScoringRule,
    komi: Double,
    prisonersBlack: Int,
    prisonersWhite: Int,
    territory: Array<IntArray>
  ): Score {
    val blackStones = board.countStones(Stone.BLACK)
    val whiteStones = board.countStones(Stone.WHITE)
    var bTerr = 0
    var wTerr = 0
    for (y in territory.indices) for (x in territory[y].indices) {
      when (territory[y][x]) {
        1 -> bTerr++; 2 -> wTerr++
      }
    }
    val blackTotal = when (rule) {
      ScoringRule.CHINESE -> (blackStones + bTerr).toDouble()
      ScoringRule.JAPANESE -> (bTerr + prisonersBlack).toDouble()
    }
    val whiteBase = when (rule) {
      ScoringRule.CHINESE -> (whiteStones + wTerr).toDouble()
      ScoringRule.JAPANESE -> (wTerr + prisonersWhite).toDouble()
    }
    val whiteTotal = whiteBase + komi
    return Score(
      rule,
      komi,
      blackStones,
      whiteStones,
      bTerr,
      wTerr,
      prisonersBlack,
      prisonersWhite,
      blackTotal,
      whiteTotal
    )
  }

  private fun marginStr(v: Double): String =
    if (v == v.toInt().toDouble()) "${v.toInt()}" else "%.1f".format(v)

  private fun resetState() {
    mainMoves.clear()
    currentBoard = Board(size)
    nextToPlay = Stone.BLACK
    prisonersBlack = 0
    prisonersWhite = 0
    endReason = EndReason.NONE
    resignedBy = null
    result = null
    finalScore = null
    finalTerritoryGrid = null

    isReview = false
    reviewBaseIndex = 0
    reviewMoves.clear()
    reviewCursor = 0

    started = false
  }

  private fun buildBoardFrom(moves: List<Move>, upTo: Int): Board {
    val b = Board(size)
    var toPlay = Stone.BLACK
    var i = 0
    while (i < upTo) {
      val m = moves[i]
      if (m.isPass) {
        toPlay = opposite(toPlay)
      } else {
        val res = b.play(Move(color = toPlay, x = m.x, y = m.y, isPass = false))
        if (!res.ok) break
        toPlay = opposite(toPlay)
      }
      i++
    }
    return b
  }

  private fun boardAndToPlayAtCursor(): Pair<Board, Stone> {
    var b = buildBoardFrom(mainMoves, upTo = reviewBaseIndex)
    var toPlay = currentToPlayAt(reviewBaseIndex)
    val uptoReview = (reviewCursor - reviewBaseIndex).coerceAtLeast(0)
    var i = 0
    while (i < uptoReview) {
      val m = reviewMoves[i]
      if (m.isPass) {
        toPlay = opposite(toPlay)
      } else {
        val res = b.play(Move(color = toPlay, x = m.x, y = m.y, isPass = false))
        if (!res.ok) break
        toPlay = opposite(toPlay)
      }
      i++
    }
    return b to toPlay
  }

  private fun recomputePrisonersFromRecord() {
    var b = Board(size)
    var toPlay = Stone.BLACK
    var pB = 0
    var pW = 0
    for (i in 0 until mainMoves.size) {
      val m = mainMoves[i]
      if (m.isPass) {
        toPlay = opposite(toPlay); continue
      }
      val res = b.play(Move(color = toPlay, x = m.x, y = m.y, isPass = false))
      if (res.ok) {
        if (toPlay == Stone.BLACK && res.capturedWhite > 0) pB += res.capturedWhite
        if (toPlay == Stone.WHITE && res.capturedBlack > 0) pW += res.capturedBlack
        toPlay = opposite(toPlay)
      } else break
    }
    prisonersBlack = pB
    prisonersWhite = pW
  }

  private fun shownBoard(): Board =
    if (!isReview) currentBoard else boardAndToPlayAtCursor().first

  private fun opposite(s: Stone) = if (s == Stone.BLACK) Stone.WHITE else Stone.BLACK

  /* ---------------- UI mapping ---------------- */

  private fun buildUi(): UI {
    val board = shownBoard()

    val lastMove = if (!isReview) {
      mainMoves.lastOrNull()?.takeIf { !it.isPass }?.let { it.x to it.y }
    } else {
      val idx = reviewCursor - 1
      when {
        idx < 0 -> null
        idx < reviewBaseIndex -> mainMoves[idx].takeIf { !it.isPass }?.let { it.x to it.y }
        else -> reviewMoves.getOrNull(idx - reviewBaseIndex)?.takeIf { !it.isPass }?.let { it.x to it.y }
      }
    }

    val stoneAtFn: (Int, Int) -> Stone = { x, y -> board.get(x, y) }
    val numberMap = if (isReview) reviewNumbersMap() else zeroMap(size)
    val moveNumFn: (Int, Int) -> Int? = { x, y -> numberMap[y][x].takeIf { it > 0 } }

    val terrFn: (Int, Int) -> TerritoryOwner = { x, y ->
      if (!isReview && finalTerritoryGrid != null && endReason != EndReason.NONE) {
        when (finalTerritoryGrid!![y][x]) {
          1 -> TerritoryOwner.BLACK; 2 -> TerritoryOwner.WHITE; 3 -> TerritoryOwner.NEUTRAL; else -> TerritoryOwner.NONE
        }
      } else TerritoryOwner.NONE
    }

    val scoreShown = if (!isReview && endReason != EndReason.NONE) finalScore else null
    val next = if (isReview) boardAndToPlayAtCursor().second else nextToPlay

    val totalShown = if (isReview) reviewBaseIndex + reviewMoves.size else mainMoves.size
    val indexShown = if (isReview) reviewCursor else mainMoves.size

    return UI(
      isPreGame = !started,
      isGameOver = endReason != EndReason.NONE,
      isReviewMode = isReview,
      reviewIndex = indexShown,
      totalMoves = totalShown,
      nextToPlay = next,
      size = size,
      komi = komi,
      rule = rule,
      prisonersBlack = prisonersBlack,
      prisonersWhite = prisonersWhite,
      resignedBy = resignedBy,
      endReason = endReason,
      result = result,
      stoneAt = stoneAtFn,
      moveNumberAt = moveNumFn,
      territoryAt = terrFn,
      lastMove = lastMove,
      score = scoreShown
    )
  }

  private fun pushUi(light: Boolean = false) {
    _ui.update { buildUi() }
  }

  /** Numbers for review stones only, up to the cursor. */
  private fun reviewNumbersMap(): Array<IntArray> {
    val n = size
    val map = Array(n) { IntArray(n) { 0 } }
    var b = buildBoardFrom(mainMoves, upTo = reviewBaseIndex)
    var toPlay = currentToPlayAt(reviewBaseIndex)
    val upto = (reviewCursor - reviewBaseIndex).coerceAtLeast(0)
    var num = 1
    var i = 0
    while (i < upto) {
      val m = reviewMoves[i]
      if (m.isPass) {
        toPlay = opposite(toPlay)
      } else {
        val res = b.play(Move(color = toPlay, x = m.x, y = m.y, isPass = false))
        if (!res.ok) break
        map[m.y][m.x] = num++
        toPlay = opposite(toPlay)
      }
      i++
    }
    return map
  }

  private fun zeroMap(n: Int): Array<IntArray> = Array(n) { IntArray(n) { 0 } }

  private fun currentToPlayAt(index: Int): Stone {
    var s = Stone.BLACK
    repeat(index) { s = opposite(s) }
    return s
  }
}
