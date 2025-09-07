@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.example.gobaneye.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.gobaneye.model.Stone
import com.example.gobaneye.vm.*
import kotlin.math.roundToInt
import android.graphics.Paint
import android.graphics.Typeface

@Composable
fun GobanScreen(viewModel: GobanViewModel) {
  val ui by viewModel.ui.collectAsState()
  val context = LocalContext.current
  val haptics = LocalHapticFeedback.current

  // Sounds
  val soundManager = remember { SoundManager(context) }
  DisposableEffect(Unit) { onDispose { soundManager.release() } }

  // Export SGF
  val exportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/x-go-sgf")
  ) { uri ->
    uri?.let {
      val text = viewModel.exportSgf()
      context.contentResolver.openOutputStream(it)?.use { os ->
        os.write(text.toByteArray())
      }
    }
  }

  // Interactions
  fun onTapPlay(x: Int, y: Int) {
    val res = viewModel.tryPlay(x, y)
    if (res.ok) {
      val captured = res.capturedBlack + res.capturedWhite
      if (captured > 0) soundManager.playCapture() else soundManager.playStone()
      haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
  }

  fun onPass() {
    viewModel.pass(); soundManager.playPass(); haptics.performHapticFeedback(HapticFeedbackType.LongPress)
  }

  fun onResign() {
    val p = ui.nextToPlay; viewModel.resign(p); soundManager.playResign(); haptics.performHapticFeedback(
      HapticFeedbackType.LongPress
    )
  }

  // Setup dialog
  var showSetup by remember { mutableStateOf(ui.isPreGame) }
  LaunchedEffect(ui.isPreGame) { showSetup = ui.isPreGame }
  if (showSetup) {
    PreGameDialog(
      defaultSize = ui.size,
      defaultKomi = ui.komi,
      defaultRule = ui.rule,
      onCancel = { showSetup = false },
      onStart = { size, komi, rule ->
        viewModel.beginGame(size, komi, rule)
        showSetup = false
      }
    )
  }

  // Live score toggle (allowed in review and during play)
  var showLiveScore by remember { mutableStateOf(false) }
  LaunchedEffect(ui.isGameOver) { if (ui.isGameOver) showLiveScore = false }

  // Result sheet
  var showScoreSheet by remember { mutableStateOf(false) }
  val scoreSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
  LaunchedEffect(ui.isGameOver, ui.score) { showScoreSheet = ui.isGameOver && ui.score != null }

  val boardEnabled = !ui.isPreGame && !ui.isGameOver

  // Live score (uses currently shown position, including review)
  val liveScore: LiveScore? by remember(
    showLiveScore, ui.lastMove, ui.prisonersBlack, ui.prisonersWhite, ui.isReviewMode, ui.reviewIndex
  ) { mutableStateOf(if (showLiveScore && !ui.isPreGame) viewModel.computeLiveScore() else null) }

  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        navigationIcon = {
          if (ui.isReviewMode) {
            TextButton(onClick = { viewModel.exitReview() }) {
              Text("Exit", maxLines = 1, softWrap = false)
            }
          }
        },
        title = {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val title = if (ui.isReviewMode) "Review Mode" else "Go Recorder"
            Text(title, fontWeight = FontWeight.SemiBold)
            val status = when {
              ui.isPreGame -> "Set up"
              ui.isReviewMode -> "Move ${ui.reviewIndex} / ${ui.totalMoves}"
              ui.isGameOver -> "Game over"
              else -> "Turn: ${if (ui.nextToPlay == Stone.BLACK) "Black ●" else "White ○"}"
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
          }
        },
        actions = {
          // Score preview toggle (enabled in review & play)
          TextButton(
            enabled = !ui.isPreGame && !ui.isGameOver,
            onClick = { showLiveScore = !showLiveScore }
          ) { Text(if (showLiveScore) "Hide Score" else "Score", maxLines = 1, softWrap = false) }

          // Export always visible
          TextButton(onClick = { exportLauncher.launch("game.sgf") }) {
            Text("Export", maxLines = 1, softWrap = false)
          }
        }
      )
    },
    bottomBar = {
      BottomAppBar(
        actions = {
          if (ui.isReviewMode) {
            // Review navigation controls
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              OutlinedButton(
                onClick = { viewModel.jumpStart(); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                enabled = ui.reviewIndex > 0
              ) { Text("Start", maxLines = 1, softWrap = false) }

              OutlinedButton(
                onClick = { viewModel.prevMove(); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                enabled = ui.reviewIndex > 0
              ) { Text("Prev", maxLines = 1, softWrap = false) }

              OutlinedButton(
                onClick = { viewModel.nextMove(); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                enabled = ui.reviewIndex < ui.totalMoves
              ) { Text("Next", maxLines = 1, softWrap = false) }

              OutlinedButton(
                onClick = { viewModel.jumpEnd(); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                enabled = ui.reviewIndex < ui.totalMoves
              ) { Text("End", maxLines = 1, softWrap = false) }
            }
          } else {
            // Regular play: Pass / Undo / Resign / Review
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              FilledTonalButton(
                enabled = !ui.isPreGame && !ui.isGameOver,
                onClick = { onPass() },
              ) { Text("Pass", maxLines = 1, softWrap = false) }

              OutlinedButton(
                enabled = !ui.isPreGame && !ui.isGameOver,
                onClick = { viewModel.undo() },
              ) { Text("Undo", maxLines = 1, softWrap = false) }

              OutlinedButton(
                enabled = !ui.isPreGame && !ui.isGameOver,
                onClick = { onResign() },
              ) { Text("Resign", maxLines = 1, softWrap = false) }

              // New Review button (replaces Back/Next in regular mode)
              FilledTonalButton(
                enabled = !ui.isPreGame,
                onClick = { viewModel.enterReview(ui.totalMoves); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
              ) { Text("Review", maxLines = 1, softWrap = false) }
            }
          }
        }
      )
    }
  ) { padding ->
    Column(
      Modifier
        .fillMaxSize()
        .padding(padding)
        .imePadding()
        .systemBarsPadding()
        .padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // Territory overlay (end or live score)
      val showTerritory = when {
        ui.isGameOver -> true
        showLiveScore && !ui.isPreGame -> true
        else -> false
      }
      val territoryAt: (Int, Int) -> TerritoryOwner = if (ui.isGameOver) {
        ui.territoryAt
      } else {
        val liveMap = liveScore?.territory
        { x, y ->
          if (liveMap == null) TerritoryOwner.NONE else when (liveMap[y][x]) {
            1 -> TerritoryOwner.BLACK
            2 -> TerritoryOwner.WHITE
            3 -> TerritoryOwner.NEUTRAL
            else -> TerritoryOwner.NONE
          }
        }
      }

      // Board
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f, fill = true),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 3.dp
      ) {
        Box(
          Modifier
            .background(Color(0xFFF2E2C4))
            .padding(10.dp)
        ) {
          GobanCanvas(
            boardSize = ui.size,
            stoneAt = ui.stoneAt,
            moveNumberAt = ui.moveNumberAt,   // numbers for review stones only
            onTap = ::onTapPlay,
            lastMove = ui.lastMove,
            enabled = boardEnabled,
            showTerritory = showTerritory,
            territoryAt = territoryAt,
            showNumbers = ui.isReviewMode
          )
        }
      }

      // Review scrubber
      if (ui.isReviewMode) {
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth()) {
          val total = ui.totalMoves
          var sliderValue by remember(ui.reviewIndex, total) { mutableFloatStateOf(ui.reviewIndex.toFloat()) }
          Slider(
            value = sliderValue,
            onValueChange = { v ->
              sliderValue = v
              viewModel.stepTo(v.roundToInt())
            },
            valueRange = 0f..total.toFloat(),
            steps = (total - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
          )
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0", style = MaterialTheme.typography.labelSmall)
            Text("${ui.reviewIndex} / $total", style = MaterialTheme.typography.labelSmall)
          }
        }
      }

      Spacer(Modifier.height(10.dp))

      // Status + New Game
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        AssistChip(
          onClick = {},
          label = {
            val label = when {
              ui.isPreGame -> "Komi ${ui.komi} • ${if (ui.rule == ScoringRule.CHINESE) "Chinese" else "Japanese"} • ${ui.size}×${ui.size}"
              ui.isReviewMode -> "Review ${ui.reviewIndex}/${ui.totalMoves}"
              ui.isGameOver -> ui.result ?: "Result pending"
              else -> "B pris: ${ui.prisonersBlack}  W pris: ${ui.prisonersWhite}"
            }
            Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
          }
        )
        FilledTonalButton(
          onClick = { viewModel.newGame() /* optionally reopen setup */ },
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) { Text("New Game", maxLines = 1, softWrap = false) }
      }

      // Live score card (also in review when toggled)
      if (showLiveScore && liveScore != null) {
        Spacer(Modifier.height(10.dp))
        LiveScoreCard(liveScore!!.score)
      }
    }
  }

  // Official result (bottom sheet)
  if (showScoreSheet && ui.score != null) {
    ModalBottomSheet(
      onDismissRequest = { showScoreSheet = false },
      sheetState = scoreSheetState
    ) {
      ResultSheet(
        ui = ui,
        onClose = { showScoreSheet = false },
        onExport = { exportLauncher.launch("game.sgf") },
        onReview = { showScoreSheet = false; viewModel.enterReview() },
        onNewGame = { showScoreSheet = false; viewModel.newGame() }
      )
    }
  }
}

/** ---------- UI helpers ---------- */

@Composable
private fun LiveScoreCard(s: Score) {
  ElevatedCard(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp)
  ) {
    Column(Modifier.padding(16.dp)) {
      Text("Live score (preview)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
      Spacer(Modifier.height(6.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Black"); Text("${"%.1f".format(s.blackTotal)}", fontWeight = FontWeight.Bold)
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("White"); Text("${"%.1f".format(s.whiteTotal)}", fontWeight = FontWeight.Bold)
      }
      Spacer(Modifier.height(6.dp))
      val prisonersNote = if (s.rule == ScoringRule.JAPANESE) "" else " (not counted)"
      Text("B: stones ${s.blackStonesOnBoard}, terr ${s.blackTerritory}, pris +${s.prisonersBlack}$prisonersNote")
      Text("W: stones ${s.whiteStonesOnBoard}, terr ${s.whiteTerritory}, pris +${s.prisonersWhite}$prisonersNote, komi +${s.komi}")
    }
  }
}

@Composable
private fun ResultSheet(
  ui: GobanViewModel.UI,
  onClose: () -> Unit,
  onExport: () -> Unit,
  onReview: () -> Unit,
  onNewGame: () -> Unit
) {
  Column(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 10.dp)
  ) {
    val blackTotal = ui.score!!.blackTotal
    val whiteTotal = ui.score!!.whiteTotal

    val title = when (ui.endReason) {
      EndReason.RESIGN ->
        if (ui.resignedBy == Stone.BLACK) "White wins by resignation" else "Black wins by resignation"

      EndReason.DOUBLE_PASS -> when {
        blackTotal > whiteTotal -> "Black wins by ${formatMargin(blackTotal - whiteTotal)}"
        whiteTotal > blackTotal -> "White wins by ${formatMargin(whiteTotal - blackTotal)}"
        else -> "Draw"
      }

      else -> "Game over"
    }

    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))

    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxWidth()
    ) {
      AssistChip(onClick = {}, label = {
        Text("Rule: ${if (ui.rule == ScoringRule.CHINESE) "Chinese" else "Japanese"}", maxLines = 1, softWrap = false)
      })
      AssistChip(onClick = {}, label = {
        val k = ui.score!!.komi
        Text("Komi: ${if (k == k.toInt().toDouble()) k.toInt() else k}", maxLines = 1, softWrap = false)
      })
      AssistChip(onClick = {}, label = {
        val endTxt = when (ui.endReason) {
          EndReason.RESIGN -> "Resign"; EndReason.DOUBLE_PASS -> "Pass-pass"; else -> "End"
        }
        Text("End: $endTxt", maxLines = 1, softWrap = false)
      })
      AssistChip(onClick = {}, label = {
        Text("RE: ${ui.result ?: ""}", maxLines = 1, softWrap = false)
      })
    }

    Spacer(Modifier.height(12.dp))
    ElevatedCard(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp)
    ) {
      Column(Modifier.padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text("Black", fontWeight = FontWeight.Medium)
          Text("${"%.1f".format(blackTotal)}", fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text("White", fontWeight = FontWeight.Medium)
          Text("${"%.1f".format(whiteTotal)}", fontWeight = FontWeight.Bold)
        }
        if (ui.endReason == EndReason.RESIGN) {
          Spacer(Modifier.height(6.dp))
          Text("Totals shown for reference (result by resignation).", style = MaterialTheme.typography.bodySmall)
        }
      }
    }

    Spacer(Modifier.height(10.dp))
    Text("Breakdown", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
    val prisonersNote = if (ui.rule == ScoringRule.JAPANESE) "" else " (not counted)"
    Text("Black: stones ${ui.score!!.blackStonesOnBoard}, territory ${ui.score!!.blackTerritory}, prisoners +${ui.score!!.prisonersBlack}$prisonersNote")
    Text("White: stones ${ui.score!!.whiteStonesOnBoard}, territory ${ui.score!!.whiteTerritory}, prisoners +${ui.score!!.prisonersWhite}$prisonersNote, komi +${ui.score!!.komi}")

    Spacer(Modifier.height(14.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      TextButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Close", maxLines = 1, softWrap = false) }
      TextButton(onClick = onExport, modifier = Modifier.weight(1f)) {
        Text(
          "Export SGF",
          maxLines = 1,
          softWrap = false
        )
      }
    }
    Spacer(Modifier.height(8.dp))
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      FilledTonalButton(onClick = onReview, modifier = Modifier.weight(1f)) {
        Text(
          "Review",
          maxLines = 1,
          softWrap = false
        )
      }
      FilledTonalButton(onClick = onNewGame, modifier = Modifier.weight(1f)) {
        Text(
          "New Game",
          maxLines = 1,
          softWrap = false
        )
      }
    }
  }
}

private fun formatMargin(m: Double): String =
  if (m == m.toInt().toDouble()) "${m.toInt()}" else "%.1f".format(m)

@Composable
private fun PreGameDialog(
  defaultSize: Int,
  defaultKomi: Double,
  defaultRule: ScoringRule,
  onCancel: () -> Unit,
  onStart: (size: Int, komi: Double, rule: ScoringRule) -> Unit
) {
  val allowedSizes = listOf(9, 13, 19)
  var size by remember { mutableStateOf(if (allowedSizes.contains(defaultSize)) defaultSize else 19) }
  var komiText by remember {
    mutableStateOf(
      if (defaultKomi == defaultKomi.toInt().toDouble()) defaultKomi.toInt().toString()
      else defaultKomi.toString()
    )
  }
  var rule by remember { mutableStateOf(defaultRule) }

  AlertDialog(
    onDismissRequest = onCancel,
    title = { Text("New Game") },
    text = {
      Column(Modifier.fillMaxWidth()) {
        Text("Board size", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          allowedSizes.forEach { opt ->
            FilterChip(
              selected = size == opt,
              onClick = { size = opt },
              label = { Text("${opt}×${opt}", maxLines = 1, softWrap = false) }
            )
          }
        }
        Spacer(Modifier.height(12.dp))
        Text("Komi", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
          value = komiText,
          onValueChange = { komiText = it.filter { c -> c.isDigit() || c == '.' } },
          singleLine = true,
          placeholder = { Text("e.g. 6.5") }
        )
        Spacer(Modifier.height(12.dp))
        Text("Rule", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          FilterChip(
            selected = rule == ScoringRule.CHINESE,
            onClick = { rule = ScoringRule.CHINESE },
            label = { Text("Chinese", maxLines = 1, softWrap = false) }
          )
          FilterChip(
            selected = rule == ScoringRule.JAPANESE,
            onClick = { rule = ScoringRule.JAPANESE },
            label = { Text("Japanese", maxLines = 1, softWrap = false) }
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = {
        val parsed = komiText.toDoubleOrNull() ?: 6.5
        onStart(size, parsed, rule)
      }) { Text("Start") }
    },
    dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
  )
}

@Composable
private fun GobanCanvas(
  boardSize: Int,
  stoneAt: (Int, Int) -> Stone,
  moveNumberAt: (Int, Int) -> Int?,
  onTap: (Int, Int) -> Unit,
  lastMove: Pair<Int, Int>?,
  enabled: Boolean,
  showTerritory: Boolean,
  territoryAt: (Int, Int) -> TerritoryOwner,
  showNumbers: Boolean,
  modifier: Modifier = Modifier
) {
  Canvas(
    modifier = modifier
      .fillMaxSize()
      .pointerInput(enabled, boardSize) {
        if (!enabled) return@pointerInput
        detectTapGestures { offset ->
          val n = boardSize
          val minSide = kotlin.math.min(size.width, size.height).toFloat()
          val cell = minSide / (n - 1)
          val insetX = (size.width - (cell * (n - 1))) / 2f
          val insetY = (size.height - (cell * (n - 1))) / 2f

          val localX = (offset.x - insetX) / cell
          val localY = (offset.y - insetY) / cell
          val gx = localX.roundToInt()
          val gy = localY.roundToInt()

          if (gx in 0 until n && gy in 0 until n) onTap(gx, gy)
        }
      }
  ) {
    val n = boardSize
    val minSide = kotlin.math.min(size.width, size.height).toFloat()
    val cell = minSide / (n - 1)
    val insetX = (size.width - (cell * (n - 1))) / 2f
    val insetY = (size.height - (cell * (n - 1))) / 2f

    // Board frame
    drawRect(
      color = Color(0xFFEAD3A2),
      topLeft = Offset(insetX - cell * 0.5f, insetY - cell * 0.5f),
      size = androidx.compose.ui.geometry.Size(
        width = (n - 1) * cell + cell,
        height = (n - 1) * cell + cell
      ),
      style = Stroke(width = cell * 0.06f)
    )

    // Grid
    for (i in 0 until n) {
      val x = insetX + i * cell
      drawLine(
        color = Color(0xFF3A2A10),
        start = Offset(x, insetY),
        end = Offset(x, insetY + (n - 1) * cell),
        strokeWidth = cell * 0.05f
      )
      val y = insetY + i * cell
      drawLine(
        color = Color(0xFF3A2A10),
        start = Offset(insetX, y),
        end = Offset(insetX + (n - 1) * cell, y),
        strokeWidth = cell * 0.05f
      )
    }

    // Hoshi
    fun hoshi(k: Int) = listOf(k, n / 2, n - 1 - k)
    val showHoshi = when (n) {
      19 -> hoshi(3).flatMap { hx -> hoshi(3).map { hy -> hx to hy } }
      13 -> listOf(3, n / 2, n - 4).flatMap { hx -> listOf(3, n / 2, n - 4).map { hy -> hx to hy } }
      9 -> listOf(2, n / 2, n - 3).flatMap { hx -> listOf(2, n / 2, n - 3).map { hy -> hx to hy } }
      else -> emptyList()
    }
    for ((hx, hy) in showHoshi) {
      drawCircle(
        color = Color(0xFF3A2A10),
        center = Offset(insetX + hx * cell, insetY + hy * cell),
        radius = cell * 0.08f
      )
    }

    // Stones
    for (y in 0 until n) for (x in 0 until n) {
      when (stoneAt(x, y)) {
        Stone.BLACK -> {
          val cx = insetX + x * cell
          val cy = insetY + y * cell
          drawCircle(
            color = Color(0x55000000),
            center = Offset(cx + cell * 0.03f, cy + cell * 0.03f),
            radius = cell * 0.46f
          )
          drawCircle(color = Color.Black, center = Offset(cx, cy), radius = cell * 0.45f)
        }

        Stone.WHITE -> {
          val cx = insetX + x * cell
          val cy = insetY + y * cell
          drawCircle(
            color = Color(0x33000000),
            center = Offset(cx + cell * 0.03f, cy + cell * 0.03f),
            radius = cell * 0.46f
          )
          drawCircle(color = Color.White, center = Offset(cx, cy), radius = cell * 0.45f)
          drawCircle(
            color = Color(0xFF444444),
            center = Offset(cx, cy),
            radius = cell * 0.45f,
            style = Stroke(width = cell * 0.02f)
          )
        }

        Stone.EMPTY -> Unit
      }
    }

    // Numbers on stones (review stones only)
    if (showNumbers) {
      val textPaint = Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
      }
      val whiteTextPaint = Paint(textPaint).apply { color = android.graphics.Color.WHITE }
      val textSize = cell * 0.36f
      textPaint.textSize = textSize
      whiteTextPaint.textSize = textSize
      val yAdjust = (textSize * 0.34f)

      for (y in 0 until n) for (x in 0 until n) {
        val num = moveNumberAt(x, y) ?: continue
        val cx = insetX + x * cell
        val cy = insetY + y * cell
        val s = stoneAt(x, y)
        drawIntoCanvas { c ->
          val p = if (s == Stone.BLACK) whiteTextPaint else textPaint
          c.nativeCanvas.drawText(num.toString(), cx, cy + yAdjust, p)
        }
      }
    }

    // Last move ring
    lastMove?.let { (lx, ly) ->
      val cx = insetX + lx * cell
      val cy = insetY + ly * cell
      drawCircle(
        color = Color(0xFF00897B),
        center = Offset(cx, cy),
        radius = cell * 0.18f,
        style = Stroke(width = cell * 0.04f)
      )
    }

    // Territory overlay
    if (showTerritory) {
      val dotR = cell * 0.14f
      for (y in 0 until n) for (x in 0 until n) {
        if (stoneAt(x, y) != Stone.EMPTY) continue
        val cx = insetX + x * cell
        val cy = insetY + y * cell
        when (territoryAt(x, y)) {
          TerritoryOwner.BLACK -> drawCircle(color = Color(0x99000000), center = Offset(cx, cy), radius = dotR)
          TerritoryOwner.WHITE -> drawCircle(
            color = Color(0x99FFFFFF),
            center = Offset(cx, cy),
            radius = dotR,
            style = Stroke(width = cell * 0.03f)
          )

          TerritoryOwner.NEUTRAL -> drawCircle(color = Color(0x55999999), center = Offset(cx, cy), radius = dotR)
          TerritoryOwner.NONE -> Unit
        }
      }
    }
  }
}
