/*
 * Copyright (c) 2026 Rekluz Games. All rights reserved.
 * This code and its assets are the exclusive property of Rekluz Games.
 * Unauthorized copying, distribution, or commercial use is strictly prohibited.
 */
// Last Updated: March 7, 2026 - Rekluz Games

package com.rekluzgames.nikakudorimahjong

import android.content.Context
import android.content.SharedPreferences
import android.media.SoundPool
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.*

class GameViewModel(context: Context) : ViewModel() {
    val game = GameModel(initialRows = 8, initialCols = 17, context = context)

    override fun onCleared() {
        super.onCleared()
        game.releaseSounds()
    }
}

data class Tile(
    val type: Int,
    val isSelected: Boolean = false,
    val isRemoved: Boolean = false,
    val imageName: String = "",
    val isHint: Boolean = false
)

enum class GameState {
    PLAYING, PAUSED, OPTIONS, SCORE, WON, NO_MOVES, ABOUT, BOARDS
}

class GameModel(initialRows: Int, initialCols: Int, val context: Context) {
    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(5)
        .build()

    private var clickSoundId = loadSoundResource(R.raw.tile_click)
    private var errorSoundId = loadSoundResource(R.raw.tile_error)
    private var matchSoundId = loadSoundResource(R.raw.tile_match)
    private var victorySoundId = loadSoundResource(R.raw.tile_tada)

    private fun loadSoundResource(resId: Int): Int {
        return if (resId != 0) {
            try {
                soundPool.load(context, resId, 1)
            } catch (e: Exception) { 0 }
        } else 0
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("ShisenShoPrefs", Context.MODE_PRIVATE)

    var rows by mutableIntStateOf(prefs.getInt("grid_rows", initialRows))
    var cols by mutableIntStateOf(prefs.getInt("grid_cols", initialCols))
    var boardMode by mutableStateOf(prefs.getString("board_mode", "standard") ?: "standard")

    var boardWidthScale by mutableFloatStateOf(calculateWidthScale(rows, cols, boardMode))
    var board by mutableStateOf(List(rows) { List(cols) { Tile(0) } })
    private var initialBoardState: List<List<Tile>> = emptyList()

    private val history = mutableStateListOf<Pair<Pair<Int, Int>, Pair<Int, Int>>>()
    val canUndo: Boolean get() = history.isNotEmpty()

    var showAutocompletePrompt by mutableStateOf(false)
    private var autoSolveSequence = mutableListOf<Pair<Pair<Int, Int>, Pair<Int, Int>>>()
    var isAutoPlaying by mutableStateOf(false)
        private set

    var selectedTile by mutableStateOf<Pair<Int, Int>?>(null)
    var gameState by mutableStateOf(GameState.PLAYING)
    var timeSeconds by mutableLongStateOf(0L)

    var shufflesRemaining by mutableIntStateOf(5)
    val canShuffle: Boolean get() = shufflesRemaining > 0

    val hintCooldownSeconds = 30L
    var lastHintTime by mutableLongStateOf(-hintCooldownSeconds)

    val isHintAvailable: Boolean get() = timeSeconds >= lastHintTime + hintCooldownSeconds
    val hintSecondsRemaining: Long get() = ((lastHintTime + hintCooldownSeconds) - timeSeconds).coerceAtLeast(0L)

    var lastPath by mutableStateOf<List<Pair<Int, Int>>?>(null)

    private val gameScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pathClearJob: Job? = null
    private var generationJob: Job? = null
    private var autocompleteJob: Job? = null

    var isSoundEnabled by mutableStateOf(prefs.getBoolean("sound_enabled", true))
        private set

    var isGenerating by mutableStateOf(false)
        private set

    fun toggleSound(enabled: Boolean) {
        isSoundEnabled = enabled
        prefs.edit { putBoolean("sound_enabled", enabled) }
        if (enabled && clickSoundId == 0) {
            clickSoundId = loadSoundResource(R.raw.tile_click)
            errorSoundId = loadSoundResource(R.raw.tile_error)
            matchSoundId = loadSoundResource(R.raw.tile_match)
            victorySoundId = loadSoundResource(R.raw.tile_tada)
        }
    }

    var bgThemeColor by mutableStateOf(Color(0xFF002147))

    private val tileTypes = listOf(
        "tile_dot_1", "tile_dot_2", "tile_dot_3", "tile_dot_4", "tile_dot_5", "tile_dot_6", "tile_dot_7", "tile_dot_8", "tile_dot_9",
        "tile_bamboo_1", "tile_bamboo_2", "tile_bamboo_3", "tile_bamboo_4", "tile_bamboo_5", "tile_bamboo_6", "tile_bamboo_7", "tile_bamboo_8", "tile_bamboo_9",
        "tile_char_1", "tile_char_2", "tile_char_3", "tile_char_4", "tile_char_5", "tile_char_6", "tile_char_7", "tile_char_8", "tile_char_9",
        "tile_wind_e", "tile_wind_s", "tile_wind_w", "tile_wind_n",
        "tile_drag_r", "tile_drag_g", "tile_drag_b"
    )

    init {
        initializeBoard()
    }

    private fun calculateWidthScale(r: Int, c: Int, mode: String): Float {
        return when {
            mode == "custom" -> 0.75f
            (r == 8 && c == 21) -> 0.75f
            (r == 7 && c == 16) || (r == 8 && c == 17) -> 0.85f
            else -> 1f
        }
    }

    fun getDifficultyLabel(r: Int = rows, c: Int = cols, mode: String = boardMode): String {
        return when {
            mode == "custom" -> "Custom"
            r <= 5 -> "Easy"
            r <= 7 && c == 16 -> "Normal"
            c >= 21 -> "Extreme"
            else -> "Hard"
        }
    }

    fun updateGridSize(newRows: Int, newCols: Int, mode: String = "standard") {
        require((newRows * newCols) % 2 == 0) { "Board must be even." }
        rows = newRows
        cols = newCols
        boardMode = mode
        boardWidthScale = calculateWidthScale(newRows, newCols, mode)
        prefs.edit {
            putInt("grid_rows", newRows)
            putInt("grid_cols", newCols)
            putString("board_mode", mode)
        }
        initializeBoard()
    }

    private fun playSound(soundId: Int) {
        if (isSoundEnabled && soundId > 0) {
            try { soundPool.play(soundId, 1f, 1f, 1, 0, 1f) } catch (e: Exception) {}
        }
    }

    fun releaseSounds() {
        pathClearJob?.cancel()
        generationJob?.cancel()
        autocompleteJob?.cancel()
        gameScope.cancel()
        soundPool.release()
    }

    fun initializeBoard() {
        generationJob?.cancel()
        val localRows = rows
        val localCols = cols
        val totalTiles = localRows * localCols
        isGenerating = true

        generationJob = gameScope.launch(Dispatchers.Default) {
            val tilesList = mutableListOf<Tile>()
            var typeIndex = 0
            while (tilesList.size < totalTiles) {
                val name = tileTypes[typeIndex % tileTypes.size]
                repeat(2) {
                    if (tilesList.size < totalTiles) {
                        tilesList.add(Tile(type = typeIndex % tileTypes.size, imageName = name))
                    }
                }
                typeIndex++
            }

            var potentialBoard: List<List<Tile>>
            var attempts = 0
            val maxAttempts = when (totalTiles) {
                in 0..80 -> 60
                in 81..120 -> 45
                in 121..150 -> 35
                else -> 30
            }

            do {
                attempts++
                tilesList.shuffle()
                potentialBoard = List(localRows) { r ->
                    List(localCols) { c -> tilesList[r * localCols + c] }
                }
                if (isBoardSolvable(potentialBoard) || attempts >= maxAttempts) break
            } while (true)

            withContext(Dispatchers.Main) {
                if (rows == localRows && cols == localCols) {
                    board = potentialBoard
                    initialBoardState = board.map { it.toList() }
                    resetGameStats()
                }
                isGenerating = false
            }
        }
    }

    private fun isBoardSolvable(originalBoard: List<List<Tile>>): Boolean {
        val working = originalBoard.map { row -> row.map { it.copy() }.toMutableList() }.toMutableList()
        while (true) {
            val move = findAnyMove(working) ?: break
            working[move.first.first][move.first.second] = working[move.first.first][move.first.second].copy(isRemoved = true)
            working[move.second.first][move.second.second] = working[move.second.first][move.second.second].copy(isRemoved = true)
        }
        return working.flatten().all { it.isRemoved }
    }

    private fun findAnyMove(current: List<List<Tile>>): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        if (current.isEmpty() || current[0].isEmpty()) return null
        val positionsByType = mutableMapOf<String, MutableList<Pair<Int, Int>>>()
        for (r in current.indices) {
            for (c in current[0].indices) {
                val t = current[r][c]
                if (!t.isRemoved) positionsByType.getOrPut(t.imageName) { mutableListOf() }.add(r to c)
            }
        }
        for (positions in positionsByType.values) {
            if (positions.size < 2) continue
            for (i in 0 until positions.size - 1) {
                for (j in i + 1 until positions.size) {
                    if (findConnectionPath(positions[i].first, positions[i].second, positions[j].first, positions[j].second, current) != null) {
                        return positions[i] to positions[j]
                    }
                }
            }
        }
        return null
    }

    private fun hasAtLeastOneMove(checkBoard: List<List<Tile>>): Boolean = findAnyMove(checkBoard) != null

    fun retryGame() {
        board = initialBoardState.map { row -> row.map { it.copy(isSelected = false, isRemoved = false, isHint = false) } }
        resetGameStats()
    }

    private fun resetGameStats() {
        selectedTile = null
        timeSeconds = 0
        shufflesRemaining = 5
        lastHintTime = -hintCooldownSeconds
        gameState = GameState.PLAYING
        lastPath = null
        history.clear()
        showAutocompletePrompt = false
        isAutoPlaying = false
        autoSolveSequence.clear()
        autocompleteJob?.cancel()
    }

    fun onTileClick(row: Int, col: Int, view: View, isAutomation: Boolean = false) {
        // Updated guard: Block user input if auto-playing, allow automation to pass
        if (gameState != GameState.PLAYING || isGenerating) return
        if (isAutoPlaying && !isAutomation) return

        // Only trigger vibration for the player
        if (!isAutomation) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        val tile = board[row][col]
        if (tile.isRemoved) return

        val currentSelection = selectedTile
        if (currentSelection == null) {
            playSound(clickSoundId)
            updateTile(row, col, tile.copy(isSelected = true))
            selectedTile = row to col
        } else {
            val (r1, c1) = currentSelection
            if (r1 == row && c1 == col) {
                updateTile(row, col, tile.copy(isSelected = false))
                selectedTile = null
            } else {
                val tile1 = board[r1][c1]
                val connectionPath = if (tile1.imageName == tile.imageName) findConnectionPath(r1, c1, row, col) else null

                if (connectionPath != null) {
                    playSound(matchSoundId)
                    lastPath = connectionPath
                    history.add((r1 to c1) to (row to col))
                    board = board.mapIndexed { r, list ->
                        list.mapIndexed { c, t ->
                            when {
                                (r == r1 && c == c1) || (r == row && c == col) -> t.copy(isRemoved = true, isSelected = false)
                                t.isHint -> t.copy(isHint = false)
                                else -> t
                            }
                        }
                    }
                    selectedTile = null
                    if (!checkWinCondition()) {
                        checkForDeadlock()
                        // Don't start a new solve if we are already auto-playing
                        if (!isAutoPlaying) checkForAutocompleteTrigger()
                    }
                    pathClearJob?.cancel()
                    pathClearJob = gameScope.launch {
                        delay(400)
                        lastPath = null
                    }
                } else {
                    playSound(errorSoundId)
                    board = board.mapIndexed { r, list ->
                        list.mapIndexed { c, t ->
                            when {
                                r == r1 && c == c1 -> t.copy(isSelected = false)
                                r == row && c == col -> t.copy(isSelected = true)
                                else -> t
                            }
                        }
                    }
                    selectedTile = row to col
                }
            }
        }
    }

    private fun checkForAutocompleteTrigger() {
        val remaining = board.flatten().count { !it.isRemoved }
        if (remaining in 2..12 && !showAutocompletePrompt) {
            autocompleteJob?.cancel()
            autocompleteJob = gameScope.launch(Dispatchers.Default) {
                val sequence = solveBoardRecursively(board)
                if (sequence != null && isActive) {
                    withContext(Dispatchers.Main) {
                        if (board.flatten().count { !it.isRemoved } in 2..12) {
                            autoSolveSequence = sequence.toMutableList()
                            showAutocompletePrompt = true
                        }
                    }
                }
            }
        }
    }

    private fun solveBoardRecursively(currentBoard: List<List<Tile>>): List<Pair<Pair<Int, Int>, Pair<Int, Int>>>? {
        if (currentBoard.isEmpty() || currentBoard[0].isEmpty()) return emptyList()
        val activeTiles = mutableListOf<Pair<Int, Int>>()
        for (r in currentBoard.indices) {
            for (c in currentBoard[0].indices) {
                if (!currentBoard[r][c].isRemoved) activeTiles.add(r to c)
            }
        }
        if (activeTiles.isEmpty()) return emptyList()

        for (i in activeTiles.indices) {
            for (j in i + 1 until activeTiles.size) {
                val p1 = activeTiles[i]
                val p2 = activeTiles[j]
                if (currentBoard[p1.first][p1.second].imageName == currentBoard[p2.first][p2.second].imageName) {
                    if (findConnectionPath(p1.first, p1.second, p2.first, p2.second, currentBoard) != null) {
                        val nextBoard = currentBoard.mapIndexed { r, row ->
                            row.mapIndexed { c, tile -> if ((r == p1.first && c == p1.second) || (r == p2.first && c == p2.second)) tile.copy(isRemoved = true) else tile }
                        }
                        val result = solveBoardRecursively(nextBoard)
                        if (result != null) return listOf(p1 to p2) + result
                    }
                }
            }
        }
        return null
    }

    fun startAutocomplete(view: View) {
        showAutocompletePrompt = false
        isAutoPlaying = true
        gameScope.launch(Dispatchers.Default) {
            val sequence = solveBoardRecursively(board)
            withContext(Dispatchers.Main) {
                if (sequence != null) {
                    sequence.forEach { pair ->
                        // Pass isAutomation = true to allow clicks through the lock
                        onTileClick(pair.first.first, pair.first.second, view, isAutomation = true)
                        delay(200)
                        onTileClick(pair.second.first, pair.second.second, view, isAutomation = true)
                        delay(150)
                    }
                }
                isAutoPlaying = false
            }
        }
    }

    private fun checkForDeadlock() { if (!hasAtLeastOneMove(board)) gameState = GameState.NO_MOVES }

    fun shuffleBoard() {
        if (!canShuffle) return
        val remainingTiles = board.flatten().filter { !it.isRemoved }.map { it.copy(isSelected = false, isHint = false) }
        var validShuffle = false
        var newBoard = board
        while (!validShuffle) {
            val shuffledTiles = remainingTiles.shuffled()
            var index = 0
            newBoard = List(rows) { r ->
                List(cols) { c -> if (!board[r][c].isRemoved && index < shuffledTiles.size) shuffledTiles[index++] else board[r][c] }
            }
            if (hasAtLeastOneMove(newBoard)) validShuffle = true
        }
        board = newBoard
        shufflesRemaining--
        selectedTile = null
        lastPath = null
        history.clear()
        showAutocompletePrompt = false
        gameState = GameState.PLAYING
        autocompleteJob?.cancel()
    }

    fun showHint() {
        if (!isHintAvailable) return
        board = board.map { row -> row.map { it.copy(isHint = false) } }
        val groups = getRemainingTileGroups()
        val validMoves = mutableListOf<Pair<Pair<Int, Int>, Pair<Int, Int>>>()
        for (group in groups.values) {
            if (group.size < 2) continue
            for (i in 0 until group.size) {
                for (j in i + 1 until group.size) {
                    if (findConnectionPath(group[i].first, group[i].second, group[j].first, group[j].second) != null) {
                        validMoves.add(group[i] to group[j])
                    }
                }
            }
        }
        if (validMoves.isNotEmpty()) {
            val hint = validMoves.random()
            board = board.mapIndexed { r, list ->
                list.mapIndexed { c, t -> if ((r == hint.first.first && c == hint.first.second) || (r == hint.second.first && c == hint.second.second)) t.copy(isHint = true) else t }
            }
            lastHintTime = timeSeconds
        }
    }

    private fun getRemainingTileGroups(): Map<String, List<Pair<Int, Int>>> {
        val groups = mutableMapOf<String, MutableList<Pair<Int, Int>>>()
        for (r in board.indices) {
            for (c in board[0].indices) {
                if (!board[r][c].isRemoved) groups.getOrPut(board[r][c].imageName) { mutableListOf() }.add(r to c)
            }
        }
        return groups
    }

    private fun checkWinCondition(): Boolean {
        if (board.all { row -> row.all { it.isRemoved } }) {
            playSound(victorySoundId)
            gameState = GameState.WON
            return true
        }
        return false
    }

    fun undoLastMove() {
        if (history.isNotEmpty()) {
            val (p1, p2) = history.removeAt(history.size - 1)
            board = board.mapIndexed { r, rowList ->
                rowList.mapIndexed { c, tile -> if ((r == p1.first && c == p1.second) || (r == p2.first && c == p2.second)) tile.copy(isRemoved = false, isSelected = false, isHint = false) else tile }
            }
            lastPath = null
            selectedTile = null
            showAutocompletePrompt = false
            if (gameState == GameState.NO_MOVES) gameState = GameState.PLAYING
            autocompleteJob?.cancel()
        }
    }

    fun saveScore(name: String, time: Long) {
        val key = "game_scores_${rows}_${cols}_${boardMode}"
        val savedString = prefs.getString(key, "") ?: ""
        val scoreList = if (savedString.isEmpty()) mutableListOf() else savedString.split("||").mapNotNull {
            val parts = it.split(":")
            if (parts.size == 2) parts[0] to (parts[1].toLongOrNull() ?: 0L) else null
        }.toMutableList()
        scoreList.add(name.uppercase().take(3) to time)
        val resultString = scoreList.sortedBy { it.second }.take(10).joinToString("||") { "${it.first}:${it.second}" }
        prefs.edit { putString(key, resultString) }
    }

    fun getTopScores(r: Int, c: Int, mode: String = boardMode): List<Pair<String, String>> {
        val key = "game_scores_${r}_${c}_${mode}"
        val savedString = prefs.getString(key, "") ?: ""
        return if (savedString.isEmpty()) emptyList() else savedString.split("||").mapNotNull {
            val parts = it.split(":")
            if (parts.size == 2) parts[0] to formatGivenTime(parts[1].toLongOrNull() ?: 0L) else null
        }
    }

    fun clearScores(r: Int, c: Int, mode: String = boardMode) { prefs.edit { remove("game_scores_${r}_${c}_${mode}") } }

    private fun updateTile(row: Int, col: Int, newTile: Tile) {
        board = board.toMutableList().apply { this[row] = this[row].toMutableList().apply { this[col] = newTile } }
    }

    private fun isWalkable(r: Int, c: Int, currentBoard: List<List<Tile>>): Boolean {
        if (r !in currentBoard.indices || c !in currentBoard[0].indices) return true
        return currentBoard[r][c].isRemoved
    }

    /**
     * Refined Shisen-Sho Pathfinding (0, 1, or 2 turns).
     */
    private fun findConnectionPath(r1: Int, c1: Int, r2: Int, c2: Int, currentBoard: List<List<Tile>> = board): List<Pair<Int, Int>>? {
        if (currentBoard.isEmpty() || currentBoard[0].isEmpty()) return null
        val rowsCount = currentBoard.size
        val colsCount = currentBoard[0].size

        if (isLineEmpty(r1, c1, r2, c2, currentBoard)) return listOf(Pair(r1, c1), Pair(r2, c2))

        if (isWalkable(r1, c2, currentBoard) && isLineEmpty(r1, c1, r1, c2, currentBoard) && isLineEmpty(r1, c2, r2, c2, currentBoard)) {
            return listOf(Pair(r1, c1), Pair(r1, c2), Pair(r2, c2))
        }
        if (isWalkable(r2, c1, currentBoard) && isLineEmpty(r1, c1, r2, c1, currentBoard) && isLineEmpty(r2, c1, r2, c2, currentBoard)) {
            return listOf(Pair(r1, c1), Pair(r2, c1), Pair(r2, c2))
        }

        for (c in -1..colsCount) {
            if (c == c1) continue
            if (isWalkable(r1, c, currentBoard) && isLineEmpty(r1, c1, r1, c, currentBoard)) {
                if (isWalkable(r2, c, currentBoard) && isLineEmpty(r1, c, r2, c, currentBoard) && isLineEmpty(r2, c, r2, c2, currentBoard)) {
                    return listOf(Pair(r1, c1), Pair(r1, c), Pair(r2, c), Pair(r2, c2))
                }
            }
        }
        for (r in -1..rowsCount) {
            if (r == r1) continue
            if (isWalkable(r, c1, currentBoard) && isLineEmpty(r1, c1, r, c1, currentBoard)) {
                if (isWalkable(r, c2, currentBoard) && isLineEmpty(r, c1, r, c2, currentBoard) && isLineEmpty(r, c2, r2, c2, currentBoard)) {
                    return listOf(Pair(r1, c1), Pair(r, c1), Pair(r, c2), Pair(r2, c2))
                }
            }
        }
        return null
    }

    private fun isLineEmpty(r1: Int, c1: Int, r2: Int, c2: Int, currentBoard: List<List<Tile>>): Boolean {
        if (currentBoard.isEmpty() || currentBoard[0].isEmpty()) return false
        if (r1 == r2) {
            val start = if (c1 < c2) c1 else c2
            val end = if (c1 < c2) c2 else c1
            for (c in start + 1 until end) if (!isWalkable(r1, c, currentBoard)) return false
            return true
        } else if (c1 == c2) {
            val start = if (r1 < r2) r1 else r2
            val end = if (r1 < r2) r2 else r1
            for (r in start + 1 until end) if (!isWalkable(r, c1, currentBoard)) return false
            return true
        }
        return false
    }

    fun formatTime(): String = formatGivenTime(timeSeconds)
    private fun formatGivenTime(s: Long): String = "%02d:%02d".format(s / 60, s % 60)
}