package com.example.eldaniel

import android.content.Context
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.inputmethod.EditorInfo
import android.text.InputType
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.atan2
import kotlin.math.hypot
import androidx.core.graphics.toColorInt

class ElDanielService : InputMethodService() {
    private var startX = 0f
    private var startY = 0f
    private var tapCount = 0
    private var currentDirection = ""
    private var lastTapTime = 0L
    private var currentWord = ""

    private val commitHandler = Handler(Looper.getMainLooper())

    private lateinit var dictionary: DictionaryManager

    // --- DYNAMIC SETTINGS ---
    private var hapticEnabled = true
    private var dynamicCommitDelay = 400L

    private var caseMode = 0
    private val modeLabels = listOf("Abc", "ABC", "abc", "123")

    // --- METRIC ENGINE TRACKING STATE ---
    enum class CounterMode { SMS, GENERAL }
    private var currentMode = CounterMode.GENERAL
    private lateinit var smsCounterTextView: TextView

    override fun onCreate() {
        super.onCreate()
        // Initialize the dictionary when the service starts
        dictionary = DictionaryManager(this)
    }

    // Loads user preferences and intelligently adapts structural tracking variations
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val prefs = getSharedPreferences("ElDanielPrefs", Context.MODE_PRIVATE)
        hapticEnabled = prefs.getBoolean("haptic_enabled", true)
        dynamicCommitDelay = prefs.getLong("commit_delay", 400L)

        // Intelligently scan input target context parameters
        if (info != null) {
            val isSms = (info.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                    && (info.inputType and InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE) != 0
            currentMode = if (isSms) CounterMode.SMS else CounterMode.GENERAL
        } else {
            currentMode = CounterMode.GENERAL
        }

        // Stabilize tracking node state on new field connection
        updateCounterDisplay("")
    }

    // --- HAPTIC FEEDBACK ENGINE ---
    private fun triggerVibration(view: View) {
        if (hapticEnabled) {
            // FIX: Removed redundant qualifier and deprecated FLAG_IGNORE_GLOBAL_SETTING
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    override fun onCreateInputView(): View {
        // FIX: Passing 'null, false' properly handles the view root and layout parameters
        val view = layoutInflater.inflate(R.layout.keyboard_view, null, false)

        val preview = view.findViewById<TextView>(R.id.preview_bubble)
        val listPreview = view.findViewById<TextView>(R.id.list_preview)
        val caseIndicator = view.findViewById<TextView>(R.id.case_indicator)
        val suggestionContainer = view.findViewById<LinearLayout>(R.id.suggestion_container)

        // Initialize Sovereign Metric Display Integration
        smsCounterTextView = view.findViewById(R.id.sms_counter_text)

        val modeButton = view.findViewById<TextView>(R.id.mode_button)
        modeButton.setOnClickListener {
            triggerVibration(it)
            caseMode = (caseMode + 1) % 4
            caseIndicator.text = modeLabels[caseMode]
            modeButton.text = modeLabels[caseMode].uppercase()
        }

        view.findViewById<View>(R.id.space_bar).setOnClickListener {
            triggerVibration(it)

            // 1. Check if there is a word to learn
            if (currentWord.isNotEmpty()) {
                // 2. Use the specific "learnLocalWord" function from your DictionaryManager
                dictionary.learnLocalWord(currentWord)
            }

            // 3. Commit the space to the input field
            currentInputConnection?.commitText(" ", 1)

            // 4. Reset the state for the next word
            currentWord = ""
            updateSuggestions("", suggestionContainer)
        }

        val sendButton = view.findViewById<ImageView>(R.id.send_button)
        sendButton.setOnClickListener {
            triggerVibration(it)
            currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEND)
        }

        view.findViewById<View>(R.id.newline_button).setOnClickListener {
            triggerVibration(it)
            currentInputConnection?.commitText("\n", 1)
            currentWord = ""
            updateSuggestions("", suggestionContainer)
        }

        view.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) v.performClick()

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    commitHandler.removeCallbacksAndMessages(null)
                    startX = event.x
                    startY = event.y

                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 400) {
                        tapCount++
                        triggerVibration(v)
                    } else {
                        tapCount = 0
                    }
                    lastTapTime = now
                    currentDirection = ""
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    tapCount++
                    triggerVibration(v)
                    if (currentDirection.isNotEmpty()) {
                        preview.text = formatChar(getCharacter(currentDirection, tapCount))
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    val dist = hypot((event.x - startX).toDouble(), (event.y - startY).toDouble())
                    if (dist > 50) {
                        val newDir = calculateDirection(startX, startY, event.x, event.y)

                        if (newDir != currentDirection) {
                            currentDirection = newDir
                            triggerVibration(v)
                        }

                        if (currentDirection == "LEFT" && dist > 280) {
                            preview.text = "⌫"
                            preview.setTextColor(Color.RED)
                            listPreview.visibility = View.INVISIBLE
                        } else {
                            preview.text = formatChar(getCharacter(currentDirection, tapCount))
                            preview.setTextColor("#FFD700".toColorInt())
                            val list = getListForDirection(currentDirection)
                            listPreview.text = list.joinToString("  ")
                            listPreview.visibility = View.VISIBLE
                        }
                        preview.visibility = View.VISIBLE
                    }
                }

                MotionEvent.ACTION_UP -> {
                    val dist = hypot((event.x - startX).toDouble(), (event.y - startY).toDouble())

                    if (dist > 50 && currentDirection.isNotEmpty()) {
                        if (currentDirection == "LEFT" && dist > 280) {
                            triggerVibration(v)
                            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                            if (currentWord.isNotEmpty()) currentWord = currentWord.dropLast(1)
                            preview.visibility = View.INVISIBLE
                            listPreview.visibility = View.INVISIBLE
                        } else {
                            commitHandler.postDelayed({
                                val char = formatChar(getCharacter(currentDirection, tapCount))
                                currentInputConnection?.commitText(char, 1)
                                currentWord += char
                                updateSuggestions(currentWord, suggestionContainer)
                                preview.visibility = View.INVISIBLE
                                listPreview.visibility = View.INVISIBLE
                            }, dynamicCommitDelay)
                        }
                    }
                }
            }
            true
        }
        return view
    }

    // --- TRACKING LIFE CYCLE INTERCEPTION ---
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        val ic = currentInputConnection
        if (ic != null) {
            val textBefore = ic.getTextBeforeCursor(1000, 0) ?: ""
            val textAfter = ic.getTextAfterCursor(1000, 0) ?: ""
            val fullText = textBefore.toString() + textAfter.toString()

            updateCounterDisplay(fullText)
        }
    }

    // --- TRACKING CALCULATION ENGINES ---
    fun updateCounterDisplay(currentText: String) {
        val totalChars = currentText.length

        if (currentMode == CounterMode.GENERAL) {
            // Universal tracking mode for forms, websites, passport bios, and documents
            smsCounterTextView.text = getString(R.string.general_char_count, totalChars)
        } else {
            // Strict Airtime SMS segment tracking mode
            val isUnicode = hasUnicode(currentText)
            val maxSingleLimit = if (isUnicode) 70 else 160
            val segmentLimit = if (isUnicode) 67 else 153

            val segments: Int
            val remainingInSegment: Int

            if (totalChars <= maxSingleLimit) {
                segments = 1
                remainingInSegment = maxSingleLimit - totalChars
            } else {
                segments = (totalChars + segmentLimit - 1) / segmentLimit
                val totalAllowedUpToCurrentSegment = segments * segmentLimit
                remainingInSegment = totalAllowedUpToCurrentSegment - totalChars
            }

            smsCounterTextView.text = getString(R.string.sms_segment_count, remainingInSegment, segments)
        }
    }

    fun hasUnicode(text: String): Boolean {
        for (codePoint in text.codePoints()) {
            if (codePoint > 0x7F) {
                return true
            }
        }
        return false
    }

    private fun getListForDirection(dir: String): List<String> {
        return if (caseMode == 3) {
            when(dir) {
                "UP" -> LanguageMapper.numLow
                "DOWN" -> LanguageMapper.numHigh
                "LEFT" -> LanguageMapper.mathSymbols
                "RIGHT" -> LanguageMapper.symbols
                "UP_LEFT" -> LanguageMapper.extraSymbols
                "UP_RIGHT" -> LanguageMapper.punctuation
                else -> emptyList()
            }
        } else {
            when(dir) {
                "UP" -> LanguageMapper.vowels
                "RIGHT" -> LanguageMapper.powerCons
                "DOWN" -> LanguageMapper.flowCons
                "LEFT" -> LanguageMapper.complexCons
                "UP_RIGHT" -> LanguageMapper.numLow
                "DOWN_RIGHT" -> LanguageMapper.numHigh
                "UP_LEFT" -> LanguageMapper.mathSymbols
                "DOWN_LEFT" -> LanguageMapper.punctuation
                else -> LanguageMapper.symbols
            }
        }
    }

    private fun getCharacter(dir: String, taps: Int): String {
        val list = getListForDirection(dir)
        if (list.isEmpty()) return ""
        return list[taps % list.size]
    }

    private fun formatChar(char: String): String {
        if (caseMode == 3) return char
        return when (caseMode) {
            1 -> char.uppercase()
            2 -> char.lowercase()
            0 -> if (currentWord.isEmpty()) char.uppercase() else char.lowercase()
            else -> char
        }
    }

    private fun updateSuggestions(word: String, container: LinearLayout?) {
        container?.removeAllViews()
        if (word.isEmpty()) return
        val suggestions = listOf(word, "${word}s", "${word}ing", "${word}ed")
        for (sug in suggestions) {
            val tv = TextView(this).apply {
                text = sug
                setPadding(30, 10, 30, 10)
                setTextColor(Color.WHITE)
                textSize = 16f
                setOnClickListener {
                    triggerVibration(this)
                    currentInputConnection?.commitText(sug.drop(word.length) + " ", 1)
                    currentWord = ""
                    container?.removeAllViews()
                }
            }
            container?.addView(tv)
        }
    }

    private fun calculateDirection(x1: Float, y1: Float, x2: Float, y2: Float): String {
        val angle = Math.toDegrees(atan2((y1 - y2).toDouble(), (x2 - x1).toDouble()))
        return when {
            angle in -22.5..22.5 -> "RIGHT"
            angle in 22.5..67.5 -> "UP_RIGHT"
            angle in 67.5..112.5 -> "UP"
            angle in 112.5..157.5 -> "UP_LEFT"
            angle > 157.5 || angle < -157.5 -> "LEFT"
            angle in -157.5..-112.5 -> "DOWN_LEFT"
            angle in -112.5..-67.5 -> "DOWN"
            angle in -67.5..-22.5 -> "DOWN_RIGHT"
            else -> "CENTER"
        }
    }
}