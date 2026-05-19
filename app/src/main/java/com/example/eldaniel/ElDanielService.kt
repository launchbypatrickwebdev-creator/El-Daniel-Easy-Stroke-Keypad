package com.example.eldaniel

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.text.InputType
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.atan2
import kotlin.math.hypot
import androidx.core.graphics.toColorInt

// 🎮 DATA MATRIX TYPE DEFINITION
data class GameProfile(
    val gameName: String,
    val useNativeGamepad: Boolean,
    val passPixelX: Float,
    val passPixelY: Float,
    val shootPixelX: Float,
    val shootPixelY: Float,
    val slidePixelX: Float,
    val slidePixelY: Float,
    val crossPixelX: Float,
    val crossPixelY: Float
)

class ElDanielService : InputMethodService() {
    // --- KEYPAD/IME TYPING TRACKING STATE ---
    private var startX = 0f
    private var startY = 0f
    private var tapCount = 0
    private var currentDirection = ""
    private var lastTapTime = 0L
    private var currentWord = ""
    private val commitHandler = Handler(Looper.getMainLooper())
    private lateinit var dictionary: DictionaryManager

    // --- SYSTEM OPTIONS ---
    private var hapticEnabled = true
    private var dynamicCommitDelay = 400L
    private var isGamingMode = false

    private var caseMode = 0
    private val modeLabels = listOf("Abc", "ABC", "abc", "123")
    enum class CounterMode { SMS, GENERAL }
    private var currentMode = CounterMode.GENERAL
    private var smsCounterTextView: TextView? = null

    // --- CHOSEN PROFILE SLOT MANAGER ---
    private var activeProfile: GameProfile = GameProfile(
        gameName = "eFootball 2026 Optimized",
        useNativeGamepad = false,
        passPixelX = 1850f, passPixelY = 750f,
        shootPixelX = 1750f, shootPixelY = 400f,
        slidePixelX = 1900f, slidePixelY = 500f,
        crossPixelX = 1600f, crossPixelY = 600f
    )

    // --- GAMING COMPONENT RUNTIME LAYOUT PARAMETERS ---
    private var leftPointerId = MotionEvent.INVALID_POINTER_ID
    private var leftPivotX = 0f
    private var leftPivotY = 0f

    private val triangleRect = Rect()
    private val squareRect = Rect()
    private val circleRect = Rect()
    private val crossRect = Rect()
    private val skillModifierRect = Rect()
    private val pauseStartRect = Rect()
    private var isSkillModifierActive = false

    override fun onCreate() {
        super.onCreate()
        dictionary = DictionaryManager(this)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val prefs = getSharedPreferences("ElDanielPrefs", Context.MODE_PRIVATE)
        hapticEnabled = prefs.getBoolean("haptic_enabled", true)
        dynamicCommitDelay = prefs.getLong("commit_delay", 400L)
        isGamingMode = prefs.getBoolean("gaming_mode_enabled", false)

        if (!isGamingMode && info != null) {
            val isSms = (info.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                    && (info.inputType and InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE) != 0
            currentMode = if (isSms) CounterMode.SMS else CounterMode.GENERAL
            updateCounterDisplay("")
        }
    }

    // 🔄 WINDOW LIFECYCLE OVERRIDE: Forces the system to render overlay over true full-screen engines
    override fun onEvaluateInputViewShown(): Boolean {
        return if (isGamingMode) true else super.onEvaluateInputViewShown()
    }

    private fun triggerVibration(view: View) {
        if (hapticEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    override fun onCreateInputView(): View {
        // ──────────────────────────────────────────────────────────────
        // SCENARIO A: THE GAMING CONTROLLER OVERLAY MODE
        // ──────────────────────────────────────────────────────────────
        if (isGamingMode) {
            val view = layoutInflater.inflate(R.layout.gamepad_overlay, null, false)

            // 🛠️ FORCE WINDOW BOUNDS TO FILL TRIPLE BUFFER GLASS canvas
            window?.window?.let { windowObj ->
                val layoutParams = windowObj.attributes
                layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
                layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT

                layoutParams.flags = layoutParams.flags or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                windowObj.attributes = layoutParams
            }

            val vPause = view.findViewById<View>(R.id.btn_pause_start)
            val vTriangle = view.findViewById<View>(R.id.btn_triangle)
            val vSquare = view.findViewById<View>(R.id.btn_square)
            val vCircle = view.findViewById<View>(R.id.btn_circle)
            val vCross = view.findViewById<View>(R.id.btn_cross)
            val vSkill = view.findViewById<View>(R.id.skill_modifier_zone)

            view.post {
                vPause?.getGlobalVisibleRect(pauseStartRect)
                vTriangle?.getGlobalVisibleRect(triangleRect)
                vSquare?.getGlobalVisibleRect(squareRect)
                vCircle?.getGlobalVisibleRect(circleRect)
                vCross?.getGlobalVisibleRect(crossRect)
                vSkill?.getGlobalVisibleRect(skillModifierRect)
            }

            view.setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    v.performClick()
                }
                handleGamingInputEvents(event)
                true
            }
            return view
        }

        // ──────────────────────────────────────────────────────────────
        // SCENARIO B: STANDARD IME TYPING SYSTEM MODE
        // ──────────────────────────────────────────────────────────────
        val view = layoutInflater.inflate(R.layout.keyboard_view, null, false)
        val preview = view.findViewById<TextView>(R.id.preview_bubble)
        val listPreview = view.findViewById<TextView>(R.id.list_preview)
        val caseIndicator = view.findViewById<TextView>(R.id.case_indicator)
        val suggestionContainer = view.findViewById<LinearLayout>(R.id.suggestion_container)
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
            if (currentWord.isNotEmpty()) dictionary.learnLocalWord(currentWord)
            currentInputConnection?.commitText(" ", 1)
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
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                v.performClick()
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    commitHandler.removeCallbacksAndMessages(null)
                    startX = event.x
                    startY = event.y
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 400) { tapCount++ ; triggerVibration(v) } else { tapCount = 0 }
                    lastTapTime = now
                    currentDirection = ""
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    tapCount++
                    triggerVibration(v)
                    if (currentDirection.isNotEmpty()) preview.text = formatChar(getCharacter(currentDirection, tapCount))
                }
                MotionEvent.ACTION_MOVE -> {
                    val dist = hypot((event.x - startX).toDouble(), (event.y - startY).toDouble())
                    if (dist > 50) {
                        val newDir = calculateDirection(startX, startY, event.x, event.y)
                        if (newDir != currentDirection) { currentDirection = newDir; triggerVibration(v) }
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

    private fun handleGamingInputEvents(event: MotionEvent) {
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)
        val x = event.getX(actionIndex)
        val y = event.getY(actionIndex)
        val screenWidth = resources.displayMetrics.widthPixels

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (x < screenWidth / 2) {
                    leftPointerId = pointerId
                    leftPivotX = x
                    leftPivotY = y
                } else {
                    val ix = x.toInt()
                    val iy = y.toInt()

                    when {
                        skillModifierRect.contains(ix, iy) -> isSkillModifierActive = true
                        pauseStartRect.contains(ix, iy) -> routeHybridAction("Pause", 0f, 0f)
                        triangleRect.contains(ix, iy) -> routeHybridAction("North", activeProfile.shootPixelX, activeProfile.shootPixelY)
                        crossRect.contains(ix, iy) -> routeHybridAction("South", activeProfile.passPixelX, activeProfile.passPixelY)
                        circleRect.contains(ix, iy) -> routeHybridAction("East", activeProfile.slidePixelX, activeProfile.slidePixelY)
                        squareRect.contains(ix, iy) -> routeHybridAction("West", activeProfile.crossPixelX, activeProfile.crossPixelY)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    if (event.getPointerId(i) == leftPointerId) {
                        val deltaX = event.getX(i) - leftPivotX
                        val deltaY = event.getY(i) - leftPivotY
                        val angle360 = Math.toDegrees(atan2(deltaY.toDouble(), deltaX.toDouble()))
                        streamVirtualJoystickAxis(deltaX, deltaY, angle360)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (pointerId == leftPointerId) {
                    leftPointerId = MotionEvent.INVALID_POINTER_ID
                    stopJoystickStream()
                } else {
                    val ix = x.toInt()
                    val iy = y.toInt()
                    if (skillModifierRect.contains(ix, iy)) isSkillModifierActive = false
                }
            }
        }
    }

    private fun routeHybridAction(quadrant: String, injectorX: Float, injectorY: Float) {
        if (isSkillModifierActive) {
            Log.d("ElDanielEngine", "Skill Mod active. Transforming input path for: $quadrant")
        }

        if (activeProfile.useNativeGamepad) {
            val keyCode = when (quadrant) {
                "North" -> KeyEvent.KEYCODE_BUTTON_Y
                "South" -> KeyEvent.KEYCODE_BUTTON_A
                "East"  -> KeyEvent.KEYCODE_BUTTON_B
                "West"  -> KeyEvent.KEYCODE_BUTTON_X
                "Pause" -> KeyEvent.KEYCODE_BUTTON_START
                else -> -1
            }
            if (keyCode != -1) {
                val time = SystemClock.uptimeMillis()
                currentInputConnection?.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, 0, InputDevice.SOURCE_GAMEPAD, 0))
                currentInputConnection?.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0, 0, InputDevice.SOURCE_GAMEPAD, 0))
            }
        }

        if (injectorX != 0f && injectorY != 0f) {
            injectSyntheticScreenTouch(injectorX, injectorY)
        }
    }

    private fun streamVirtualJoystickAxis(dx: Float, dy: Float, angle: Double) {
        val maxRadius = 150f
        val axisX = (dx / maxRadius).coerceIn(-1f, 1f)
        val axisY = (dy / maxRadius).coerceIn(-1f, 1f)
        Log.d("ElDanielJoystick", "Axis X: $axisX, Axis Y: $axisY, Angle: $angle")
    }

    private fun stopJoystickStream() {
        Log.d("ElDanielJoystick", "Joystick centered.")
    }

    private fun injectSyntheticScreenTouch(tx: Float, ty: Float) {
        val time = SystemClock.uptimeMillis()
        val prop = arrayOf(MotionEvent.PointerProperties().apply { id = 5; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coord = arrayOf(MotionEvent.PointerCoords().apply { x = tx; y = ty; pressure = 1.0f })

        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 1, prop, coord, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        val up = MotionEvent.obtain(time, time + 30, MotionEvent.ACTION_UP, 1, prop, coord, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)

        down.recycle()
        up.recycle()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (isGamingMode) return
        val ic = currentInputConnection
        if (ic != null) {
            val textBefore = ic.getTextBeforeCursor(1000, 0) ?: ""
            val textAfter = ic.getTextAfterCursor(1000, 0) ?: ""
            updateCounterDisplay(textBefore.toString() + textAfter.toString())
        }
    }

    private fun updateCounterDisplay(currentText: String) {
        val totalChars = currentText.length
        val textView = smsCounterTextView ?: return

        if (currentMode == CounterMode.GENERAL) {
            textView.text = getString(R.string.general_char_count, totalChars)
        } else {
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
                remainingInSegment = (segments * segmentLimit) - totalChars
            }
            textView.text = getString(R.string.sms_segment_count, remainingInSegment, segments)
        }
    }

    private fun hasUnicode(text: String): Boolean {
        for (codePoint in text.codePoints()) { if (codePoint > 0x7F) return true }
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

        val suggestions = dictionary.getSuggestions(word)
        val displayList = suggestions.ifEmpty { listOf(word) }

        for (sug in displayList) {
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