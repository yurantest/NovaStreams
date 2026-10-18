package com.novastreams.app.ui.components

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.view.WindowManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SpeakerNotesOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.novastreams.app.appContainer
import com.novastreams.app.ui.theme.LocalNovaColors
import com.novastreams.app.ui.theme.NovaColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * Веб-чат Twitch. Клавиатура — нативно WebView при фокусе в поле ввода (без отдельной кнопки).
 *
 * На TV фокус держит [CursorHostLayout], иначе D-pad остаётся у плеера.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TwitchWebPanel(
    channelLogin: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    panelAlpha: Float = 1f,
    /** Другая стартовая страница вместо чата канала (например, страница подтверждения кода при входе на ТВ). */
    startUrl: String? = null,
    /** Заголовок панели; по умолчанию «Чат». */
    title: String = "Чат",
    /**
     * Навигация пультом «как по ссылкам»: стрелки выбирают ближайшую кнопку/ссылку/поле на странице (с подсветкой)
     * и прокручивают страницу, OK нажимает. Нужна для страниц, где обычный фокус WebView не работает
     * (страница подтверждения кода Twitch: «Разрешить» лежит ниже экрана, и дойти до неё стрелками нельзя).
     */
    remoteNavigator: Boolean = false,
    /**
     * false — панель свёрнута, но WebView остаётся живым в фоне (страница и соединение чата не перезагружаются).
     * Свёрнутая панель не берёт фокус и не обрабатывает клавиши. Разворачивание — просто active = true.
     */
    active: Boolean = true,
    /** false — без кнопки «Закрыть» справа вверху (закрытие сбоку/свайпом в оверлее плеера). */
    showCloseButton: Boolean = true,
) {
    val context = LocalContext.current
    val isTv = com.novastreams.app.ui.theme.LocalIsTv.current
    // На Android TV оставляем только управление стрелками и фокусом WebView.
    val useCursor = false

    val login = channelLogin.filter { it.isLetterOrDigit() || it == '_' }
    val url = startUrl ?: "https://www.twitch.tv/popout/$login/chat?popout=&darkpopout"
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var hostView by remember { mutableStateOf<CursorHostLayout?>(null) }
    val accent = LocalNovaColors.current.accent

    var cursorX by remember { mutableFloatStateOf(0.5f) }
    var cursorY by remember { mutableFloatStateOf(0.4f) }
    var clickFlash by remember { mutableStateOf(false) }
    // true — пользователь в поле ввода: фокус у WebView, курсор не перехватывает клавиши.
    var typingMode by remember { mutableStateOf(false) }
    var escapedOnce by remember { mutableStateOf(false) }
    // Подсказка управления — только Android TV (на телефоне не нужна).
    val hintKey = if (startUrl == null) "hint_seen_chat" else "hint_seen_login"
    val hintPrefs = remember { context.getSharedPreferences("nova_webchat", Context.MODE_PRIVATE) }
    var showHint by remember {
        mutableStateOf(isTv && !hintPrefs.getBoolean(hintKey, false))
    }
    // Фокус на угловой кнопке: цикл возврата фокуса в WebView её не перебивает.
    var overlayFocused by remember { mutableStateOf(false) }
    fun dismissHint() {
        if (showHint) {
            showHint = false
            try {
                hintPrefs.edit().putBoolean(hintKey, true).apply()
            } catch (_: Exception) {
            }
        }
    }
    LaunchedEffect(showHint) {
        if (showHint) {
            delay(12_000)
            dismissHint()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
            hostView = null
        }
    }

    // Пока панель на экране — возвращаем фокус host/WebView (плеер его отбирает).
    // В режиме ввода фокус остаётся у WebView, цикл не отбирает его.
    LaunchedEffect(hostView, useCursor, typingMode, active) {
        val host = hostView ?: return@LaunchedEffect
        while (isActive) {
            if (!active || overlayFocused) {
                delay(250)
                continue
            }
            if (typingMode) {
                val wv = webView
                if (wv != null && !wv.hasFocus()) {
                    wv.isFocusable = true
                    wv.isFocusableInTouchMode = true
                    wv.requestFocus()
                }
            } else if (useCursor) {
                if (!host.hasFocus()) host.requestFocus()
            } else {
                val wv = webView
                if (wv != null && !wv.hasFocus()) {
                    wv.isFocusable = true
                    wv.isFocusableInTouchMode = true
                    wv.requestFocus()
                }
            }
            delay(250)
        }
    }

    LaunchedEffect(clickFlash) {
        if (clickFlash) {
            delay(140)
            clickFlash = false
        }
    }

    fun moveCursor(dx: Float, dy: Float, wv: WebView?) {
        if (dy < 0f && cursorY <= 0.06f) {
            wv?.scrollBy(0, -140)
            wv?.evaluateJavascript("try{window.scrollBy(0,-140);}catch(e){}", null)
        } else if (dy > 0f && cursorY >= 0.94f) {
            wv?.scrollBy(0, 140)
            wv?.evaluateJavascript("try{window.scrollBy(0,140);}catch(e){}", null)
        }
        cursorX = (cursorX + dx).coerceIn(0.02f, 0.98f)
        cursorY = (cursorY + dy).coerceIn(0.02f, 0.98f)
    }

    fun exitTypingMode() {
        if (!typingMode) return
        typingMode = false
        hostView?.typingActive = false
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(webView?.windowToken, 0)
        hostView?.cursorMode = false
        hostView?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        webView?.let { wv -> if (!wv.hasFocus()) wv.requestFocus() }
    }

    fun enterTypingMode(wv: WebView) {
        typingMode = true
        hostView?.typingActive = true
        hostView?.onExitTyping = { exitTypingMode() }
        hostView?.cursorMode = false
        hostView?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.requestFocus()
        // На TV IME почти никогда не всплывает без явного showSoftInput.
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        wv.post {
            imm?.restartInput(wv)
            imm?.showSoftInput(wv, InputMethodManager.SHOW_FORCED)
        }
        (context as? Activity)?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
    }

    // Сворачивание/разворачивание без перезагрузки: скрываем View и убираем фокус / клавиатуру.
    LaunchedEffect(active, hostView) {
        val host = hostView ?: return@LaunchedEffect
        if (!active) {
            exitTypingMode()
            webView?.clearFocus()
            host.clearFocus()
            host.visibility = android.view.View.INVISIBLE
        } else {
            host.visibility = android.view.View.VISIBLE
            host.post {
                webView?.let { wv ->
                    wv.isFocusable = true
                    wv.isFocusableInTouchMode = true
                    wv.requestFocus()
                }
            }
        }
    }

    /**
     * Назад по слоям, как в браузере:
     * 1) выход из поля ввода / скрыть IME
     * 2) история WebView (настройки, другие страницы чата)
     * 3) закрыть модалку на странице (Escape / кнопка Close)
     * 4) закрыть панель чата
     */
    fun handleBack(): Boolean {
        if (typingMode) {
            exitTypingMode()
            escapedOnce = false
            return true
        }
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            escapedOnce = false
            return true
        }
        // Открыт слой поверх чата (панель смайлов, меню, окно подтверждения) — закрываем его.
        // Иначе Back закрывает панель. Раньше тут был «history.back()», который почти всегда
        // «успешно» ничего не делал, из-за чего Back переставал закрывать чат.
        if (wv != null) {
            wv.evaluateJavascript("window.__novaCloseLayer ? window.__novaCloseLayer() : 'none'") { res ->
                val r = res?.trim()?.trim('"') ?: "none"
                if (r != "closed") onClose()
            }
            return true
        }
        onClose()
        return true
    }


    fun doClick(wv: WebView?) {
        val view = wv ?: return
        clickFlash = true
        val x = cursorX * view.width.coerceAtLeast(1)
        val y = cursorY * view.height.coerceAtLeast(1)
        dispatchClick(view, x, y)
        // Фокус поля чата + системная клавиатура.
        view.postDelayed({
            view.evaluateJavascript(
                """
                (function(){
                  var x = ${x.toInt()}, y = ${y.toInt()};
                  var el = document.elementFromPoint(x, y);
                  function isInput(n){
                    if(!n) return false;
                    var tag = (n.tagName||'').toUpperCase();
                    if(tag==='INPUT'||tag==='TEXTAREA') return true;
                    if(n.isContentEditable) return true;
                    if(n.getAttribute && n.getAttribute('data-a-target')==='chat-input') return true;
                    if(n.getAttribute && (n.getAttribute('role')==='textbox')) return true;
                    return false;
                  }
                  var t = el;
                  for(var i=0;i<6 && t;i++){
                    if(isInput(t)){ try{t.focus();t.click();}catch(e){} return '1'; }
                    t = t.parentElement;
                  }
                  var sels = [
                    'textarea[data-a-target="chat-input"]',
                    '[data-a-target="chat-input"]',
                    'div[data-a-target="chat-input"]',
                    'textarea',
                    '[contenteditable="true"]',
                    'input[type="text"]'
                  ];
                  for(var s=0;s<sels.length;s++){
                    var n = document.querySelector(sels[s]);
                    if(n){
                      var r = n.getBoundingClientRect();
                      if(x>=r.left-8 && x<=r.right+8 && y>=r.top-8 && y<=r.bottom+8){
                        try{n.focus();n.click();}catch(e){}
                        return '1';
                      }
                    }
                  }
                  var e = document.activeElement;
                  if(isInput(e)) return '1';
                  return '0';
                })();
                """.trimIndent(),
            ) { res ->
                if (res != null && res.contains('1')) {
                    enterTypingMode(view)
                }
            }
        }, 120)
    }

    Column(
        modifier
            .alpha(panelAlpha)
            .background(NovaColors.Background),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            val cursorR = 20.dp
            val cursorPx = with(density) { cursorR.toPx() }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val host = CursorHostLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        isFocusable = true
                        isFocusableInTouchMode = true
                        isClickable = true
                        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    }

                    val wv = WebView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(0xFF0E0E10.toInt())
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.textZoom = 115
                        // Автозаполнение паролей (Google Password Manager и др.) в окне входа Twitch: подсказка системе,
                        // что поля WebView можно заполнять. Работает, только если в системе включена служба автозаполнения.
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
                        }
                        // IME: WebView должен уметь принимать фокус при клике по input.
                        isFocusable = true
                        isFocusableInTouchMode = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                view?.evaluateJavascript(REMOTE_NAV_JS, null)
                                view?.evaluateJavascript(
                                    """
                                    (function(){
                                      if (window.__novaConsentHook) return 'hooked';
                                      window.__novaConsentHook = true;
                                      var texts = ['Accept','Принять','Accept All','Принять все','I Accept','Agree','Согласен','OK','Хорошо','Понятно'];
                                      function tryClick(){
                                        try {
                                          var nodes = document.querySelectorAll('button, [role="button"], a, input[type="button"], [data-a-target*="consent"], [data-a-target*="cookie"]');
                                          for (var i = 0; i < nodes.length; i++) {
                                            var el = nodes[i];
                                            if (!el || el.offsetParent === null) continue;
                                            var t = (el.innerText || el.textContent || el.value || el.getAttribute('aria-label') || '').trim();
                                            for (var j = 0; j < texts.length; j++) {
                                              if (t.indexOf(texts[j]) >= 0) {
                                                el.click();
                                                return true;
                                              }
                                            }
                                          }
                                        } catch(e) {}
                                        return false;
                                      }
                                      if (tryClick()) return 'ok';
                                      var tries = 0;
                                      var timer = setInterval(function(){
                                        tries++;
                                        if (tryClick() || tries >= 20) clearInterval(timer);
                                      }, 500);
                                      try {
                                        var mo = new MutationObserver(function(){ tryClick(); });
                                        mo.observe(document.documentElement, {childList:true, subtree:true});
                                        setTimeout(function(){ try { mo.disconnect(); } catch(e){} }, 15000);
                                      } catch(e) {}
                                      return 'watching';
                                    })();
                                    """.trimIndent(),
                                    null,
                                )
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val h = request?.url?.host.orEmpty()
                                return !(h == "twitch.tv" || h.endsWith(".twitch.tv"))
                            }
                        }
                        loadUrl(url)
                    }

                    host.addView(wv)
                    host.cursorMode = false
                    host.onDpad = { keyCode, repeat ->
                        if (!host.cursorMode) {
                            if (keyCode == KeyEvent.KEYCODE_BACK) {
                                onClose()
                                true
                            } else {
                                false
                            }
                        } else {
                            val boost = 1f + (repeat.coerceAtMost(10) * 0.2f)
                            val step = 0.04f * boost
                            when (keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    moveCursor(-step, 0f, wv)
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    moveCursor(step, 0f, wv)
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    moveCursor(0f, -step, wv)
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    moveCursor(0f, step, wv)
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_CENTER,
                                KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_NUMPAD_ENTER,
                                -> {
                                    doClick(wv)
                                    true
                                }
                                KeyEvent.KEYCODE_BACK -> {
                                    host.onBackNav?.invoke() == true
                                }
                                else -> false
                            }
                        }
                    }
                    host.onBackNav = { handleBack() }
                    host.onExitTyping = { exitTypingMode() }
                    host.onAnyKey = { dismissHint() }
                    host.onMenu = { showHint = true }
                    host.navMode = true
                    host.onNav = { keyCode ->
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> wv.evaluateJavascript("window.__novaNav && window.__novaNav('up')", null)
                            KeyEvent.KEYCODE_DPAD_DOWN -> wv.evaluateJavascript("window.__novaNav && window.__novaNav('down')", null)
                            KeyEvent.KEYCODE_DPAD_LEFT -> wv.evaluateJavascript("window.__novaNav && window.__novaNav('left')", null)
                            KeyEvent.KEYCODE_DPAD_RIGHT -> wv.evaluateJavascript("window.__novaNav && window.__novaNav('right')", null)
                            else -> wv.evaluateJavascript("window.__novaClick ? window.__novaClick() : 'none'") { res ->
                                // Нажали на поле ввода — включаем режим ввода (клавиатура).
                                if (res != null && res.contains("input")) {
                                    enterTypingMode(wv)
                                } else {
                                    // Клик по кнопке (например «смайлы») Twitch отдаёт фокус в поле чата —
                                    // клавиатуру без явного входа в режим ввода не показываем.
                                    wv.postDelayed({
                                        if (!typingMode) {
                                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                            imm?.hideSoftInputFromWindow(wv.windowToken, 0)
                                        }
                                    }, 200)
                                }
                            }
                        }
                    }
                    host.post { host.requestFocus() }
                    webView = wv
                    hostView = host
                    host
                },
                update = { host ->
                    hostView = host
                    host.onBackNav = { handleBack() }
                    host.onExitTyping = { exitTypingMode() }
                    if (!typingMode) {
                        host.cursorMode = false
                        host.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                        webView?.let { wv ->
                            wv.isFocusable = true
                            wv.isFocusableInTouchMode = true
                            if (!wv.hasFocus()) host.post { wv.requestFocus() }
                        }
                    }
                },
            )

            // Подсказка «i» — только на TV.
            if (isTv) {
                CornerIconButton(
                    icon = Icons.Outlined.Info,
                    description = "Подсказка по управлению",
                    onClick = { showHint = true },
                    onFocus = { overlayFocused = it },
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                )
            }
            // На ТВ — «Назад»; на телефоне кнопка справа, если не отключена (оверлей плеера закрывает сбоку/свайпом).
            if (!isTv && showCloseButton) {
                // Сдвиг влево: справа в веб-чате кнопка фильтра пользователей Twitch.
                CornerIconButton(
                    icon = if (startUrl == null) Icons.Filled.SpeakerNotesOff else Icons.Filled.Close,
                    description = "Закрыть",
                    onClick = onClose,
                    onFocus = { overlayFocused = it },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 52.dp),
                )
            }
            if (showHint) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .padding(20.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .clickable { dismissHint() }
                        .padding(16.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (startUrl == null) "Управление чатом" else "Вход в Twitch",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                        )
                        Text(
                            text = "Стрелки пульта — выбор кнопки, смайла или поля, OK — нажать.\n" +
                                "Поле ввода: OK — клавиатура, Назад — выйти из поля.\n" +
                                "Назад — закрыть всплывающую панель (смайлы, меню), а затем — сам чат.\n" +
                                "Кнопка «i» слева вверху (на пульте — Меню) — показать это окно снова" +
                                (if (isTv) "." else ", перечёркнутый значок справа вверху — закрыть."),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.9f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            if (useCursor) {
                val cx = (cursorX * wPx - cursorPx).roundToInt()
                val cy = (cursorY * hPx - cursorPx).roundToInt()
                Box(
                    modifier = Modifier
                        .offset { IntOffset(cx, cy) }
                        .size(cursorR * 2),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val r = size.minDimension / 2f
                        val ring = if (clickFlash) accent else Color.White.copy(alpha = 0.9f)
                        drawCircle(color = Color.Black.copy(alpha = 0.25f), radius = r)
                        drawCircle(
                            color = Color.White.copy(alpha = if (clickFlash) 0.4f else 0.2f),
                            radius = r * 0.95f,
                        )
                        drawCircle(
                            color = ring,
                            radius = r * 0.9f,
                            style = Stroke(width = 3.5.dp.toPx()),
                        )
                        drawCircle(color = ring, radius = r * 0.2f)
                        val arm = r * 0.55f
                        drawLine(
                            ring,
                            Offset(center.x - arm, center.y),
                            Offset(center.x + arm, center.y),
                            strokeWidth = 2.dp.toPx(),
                        )
                        drawLine(
                            ring,
                            Offset(center.x, center.y - arm),
                            Offset(center.x, center.y + arm),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CornerIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = if (focused) 0.8f else 0.45f))
            .border(2.dp, if (focused) Color.White else Color.Transparent, CircleShape)
            .onFocusChanged {
                focused = it.isFocused
                onFocus(it.isFocused)
            }
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
    }
}

private class CursorHostLayout(context: Context) : FrameLayout(context) {
    var onDpad: ((keyCode: Int, repeatCount: Int) -> Boolean)? = null
    var cursorMode: Boolean = false
    var typingActive: Boolean = false
    var onExitTyping: (() -> Unit)? = null
    /** Единый обработчик Back (ввод → история → модалка → закрыть). */
    var onBackNav: (() -> Boolean)? = null
    /** Режим «навигации по странице»: стрелки и OK уходят в [onNav] (JS-навигатор), а не в WebView. */
    var navMode: Boolean = false
    var onNav: ((keyCode: Int) -> Unit)? = null
    /** Любое нажатие клавиши (не потребляется): прячет подсказку. */
    var onAnyKey: (() -> Unit)? = null
    /** Кнопка «Меню» на пульте: показать подсказку снова. */
    var onMenu: (() -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        isClickable = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { requestFocus() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_MENU) {
                onMenu?.invoke()
                return true
            }
            onAnyKey?.invoke()
        }
        val isNav = code == KeyEvent.KEYCODE_DPAD_LEFT ||
            code == KeyEvent.KEYCODE_DPAD_RIGHT ||
            code == KeyEvent.KEYCODE_DPAD_UP ||
            code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_CENTER ||
            code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            code == KeyEvent.KEYCODE_BACK

        // Back всегда через единый стек (ввод → история → модалка → закрыть).
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (onBackNav?.invoke() == true) return true
                if (onDpad?.invoke(code, event.repeatCount) == true) return true
            }
            return true
        }
        // Режим ввода: остальные клавиши — в WebView (текст).
        if (typingActive) {
            return super.dispatchKeyEvent(event)
        }
        if (navMode && isNav) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                // OK не повторяем при удержании, чтобы не нажать кнопку несколько раз.
                val isOk = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                    code == KeyEvent.KEYCODE_NUMPAD_ENTER
                if (!isOk || event.repeatCount == 0) onNav?.invoke(code)
            }
            return true
        }
        if (isNav && cursorMode) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (onDpad?.invoke(code, event.repeatCount) == true) return true
            }
            if (event.action == KeyEvent.ACTION_UP) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (cursorMode && onDpad?.invoke(keyCode, event.repeatCount) == true) return true
        return super.onKeyDown(keyCode, event)
    }
}

private fun dispatchClick(webView: WebView, x: Float, y: Float) {
    val downTime = SystemClock.uptimeMillis()
    val xx = x.coerceAtLeast(1f)
    val yy = y.coerceAtLeast(1f)
    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, xx, yy, 0)
    val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, xx, yy, 0)
    webView.dispatchTouchEvent(down)
    webView.dispatchTouchEvent(up)
    down.recycle()
    up.recycle()
    webView.evaluateJavascript(
        """
        (function(){
          var el = document.elementFromPoint(${xx.toInt()}, ${yy.toInt()});
          if(!el) return;
          try { el.focus(); } catch(e) {}
          try { el.click(); } catch(e) {}
          var t = el;
          for (var i = 0; i < 5 && t; i++) {
            if (t.tagName === 'BUTTON' || t.getAttribute('role') === 'button' ||
                (t.getAttribute && t.getAttribute('data-a-target'))) {
              try { t.click(); } catch(e) {}
              break;
            }
            t = t.parentElement;
          }
        })();
        """.trimIndent(),
        null,
    )
}


/**
 * Навигатор для пульта, вставляется в страницу. window.__novaNav(dir) выбирает ближайший в этом направлении
 * интерактивный элемент (кнопка, ссылка, поле, переключатель), подсвечивает его и прокручивает к нему;
 * если подходящего нет — прокручивает страницу или её внутренний прокручиваемый блок.
 * window.__novaClick() нажимает на выбранный элемент; для поля ввода возвращает 'input'.
 */
private const val REMOTE_NAV_JS = """
(function(){
  if (window.__novaNav) return 'already';
  var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=menuitem],[tabindex]:not([tabindex="-1"]),[data-a-target],[data-test-selector],[aria-label][class*="utton"]';
  var cur = null;
  function vis(el){
    if (el.disabled) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return false;
    var s = getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none' && s.opacity !== '0';
  }
  function cands(){
    var all = Array.prototype.filter.call(document.querySelectorAll(SEL), vis);
    // Оставляем самые вложенные элементы: обёртки вокруг кнопок (и «длинное» поле, внутри которого лежат кнопки)
    // не должны перетягивать выбор. Поля ввода оставляем всегда.
    return all.filter(function(e){
      var t = (e.tagName || '').toUpperCase();
      if (t === 'TEXTAREA' || t === 'INPUT' || e.isContentEditable) return true;
      return !all.some(function(o){ return o !== e && e.contains(o); });
    });
  }
  function mark(el){
    if (cur && cur !== el) { cur.style.outline = cur.__novaOutline || ''; cur.style.outlineOffset = cur.__novaOffset || ''; }
    if (cur !== el) { el.__novaOutline = el.style.outline; el.__novaOffset = el.style.outlineOffset; }
    el.style.outline = '4px solid #9147FF';
    el.style.outlineOffset = '3px';
    cur = el;
    try { el.focus({preventScroll: true}); } catch (e) {}
    try { el.scrollIntoView({block: 'center', inline: 'nearest'}); } catch (e) {}
  }
  function scroller(){
    var se = document.scrollingElement || document.documentElement;
    if (se.scrollHeight > se.clientHeight + 4) return se;
    var best = null, bh = 0, all = document.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      var e = all[i], s = getComputedStyle(e);
      if ((s.overflowY === 'auto' || s.overflowY === 'scroll') && e.scrollHeight > e.clientHeight + 4 && e.clientHeight > bh) {
        best = e; bh = e.clientHeight;
      }
    }
    return best || se;
  }
  function scrollDir(dir){
    var s = scroller(), d = Math.round(window.innerHeight * 0.5);
    if (dir === 'up') s.scrollTop -= d;
    else if (dir === 'down') s.scrollTop += d;
    else if (dir === 'left') s.scrollLeft -= d;
    else s.scrollLeft += d;
    return 'scroll';
  }
  // Слои поверх страницы: панель смайлов, меню, окна подтверждения. Пока слой открыт, стрелки ходят только по нему.
  var LAYER = '[role=dialog],[aria-modal=true],[role=menu],[role=listbox],[class*="emote-picker"],[data-a-target*="emote-picker"],[class*="balloon"],[class*="Balloon"],[class*="modal"],[class*="popover"],[class*="Popover"]';
  function layerEls(){
    var all = Array.prototype.filter.call(document.querySelectorAll(LAYER), function(e){ return vis(e) && e.querySelector(SEL); });
    // оставляем самые внешние (внутренний слой входит во внешний), берём последний по порядку в DOM
    var outer = all.filter(function(e){ return !all.some(function(o){ return o !== e && o.contains(e); }); });
    return outer.length ? outer[outer.length - 1] : null;
  }
  function ctr(el){ var r = el.getBoundingClientRect(); return {x: r.left + r.width / 2, y: r.top + r.height / 2}; }
  window.__novaCloseLayer = function(){
    var L = layerEls();
    if (!L) { window.__novaLastLayer = null; window.__novaLayerTries = 0; return 'none'; }
    // Защита от «ловушки»: если слой не закрылся после двух попыток — отдаём Back приложению (закрыть чат).
    if (window.__novaLastLayer === L) window.__novaLayerTries = (window.__novaLayerTries || 0) + 1;
    else { window.__novaLastLayer = L; window.__novaLayerTries = 1; }
    if (window.__novaLayerTries > 2) { window.__novaLastLayer = null; window.__novaLayerTries = 0; return 'none'; }
    var closeSel = 'button[aria-label="Close"],button[aria-label="Закрыть"],[data-a-target="modal-close-button"],[data-a-target="modal-close"],button[aria-label="Back"],button[aria-label="Назад"]';
    var x = L.querySelector(closeSel);
    if (x && vis(x)) { x.click(); return 'closed'; }
    var tgt = document.activeElement || document.body;
    ['keydown', 'keyup'].forEach(function(t){
      var ev = new KeyboardEvent(t, {key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true});
      tgt.dispatchEvent(ev);
      if (tgt !== document.body) document.body.dispatchEvent(ev);
    });
    // если Escape не помог — повторное нажатие на кнопку смайлов закрывает панель
    setTimeout(function(){
      if (layerEls()) {
        var t = document.querySelector('[data-a-target="emote-picker-button"],button[aria-label*="моци"],button[aria-label*="moti"]');
        if (t) t.click();
      }
    }, 120);
    return 'closed';
  };
  window.__novaNav = function(dir){
    var L = layerEls();
    var els = cands();
    if (L) { var inL = els.filter(function(e){ return L.contains(e); }); if (inL.length) els = inL; else L = null; }
    if (!els.length) return scrollDir(dir);
    var base = (cur && document.contains(cur) && vis(cur) && (!L || L.contains(cur))) ? cur : null;
    var best = null, bs = 1e12;
    if (!base) {
      // Ничего не выбрано: вниз/вправо — самый верхний элемент, вверх/влево — самый нижний.
      for (var i = 0; i < els.length; i++) {
        var c0 = ctr(els[i]);
        var sc = (dir === 'up' || dir === 'left') ? -(c0.y * 10000 + c0.x) : (c0.y * 10000 + c0.x);
        if (sc < bs) { bs = sc; best = els[i]; }
      }
      mark(best);
      return 'mark';
    }
    var from = ctr(base);
    var bb = base.getBoundingClientRect();
    for (var j = 0; j < els.length; j++) {
      var el = els[j];
      if (el === base || base.contains(el) || el.contains(base)) continue;
      var r = el.getBoundingClientRect(), c = ctr(el), main, side, align;
      // Расстояние между краями прямоугольников, а не между центрами: длинное поле ввода не «притягивает» к себе
      // выбор, когда рядом есть маленькая кнопка.
      var gapX = Math.max(0, Math.max(r.left - bb.right, bb.left - r.right));
      var gapY = Math.max(0, Math.max(r.top - bb.bottom, bb.top - r.bottom));
      if (dir === 'down') { if (c.y <= from.y + 2) continue; main = gapY; side = gapX; align = Math.abs(c.x - from.x); }
      else if (dir === 'up') { if (c.y >= from.y - 2) continue; main = gapY; side = gapX; align = Math.abs(c.x - from.x); }
      else if (dir === 'right') { if (c.x <= from.x + 2) continue; main = gapX; side = gapY; align = Math.abs(c.y - from.y); }
      else { if (c.x >= from.x - 2) continue; main = gapX; side = gapY; align = Math.abs(c.y - from.y); }
      var score = main + side * 3 + align * 0.25 + 1;
      if (score < bs) { bs = score; best = el; }
    }
    if (best) { mark(best); return 'mark'; }
    return scrollDir(dir);
  };
  window.__novaClick = function(){
    var el = (cur && document.contains(cur)) ? cur : document.activeElement;
    if (!el || el === document.body) return 'none';
    var tag = (el.tagName || '').toUpperCase();
    var type = ((el.getAttribute && el.getAttribute('type')) || '').toLowerCase();
    var isInput = tag === 'TEXTAREA' || el.isContentEditable ||
      (tag === 'INPUT' && ['checkbox', 'radio', 'button', 'submit', 'reset', 'file', 'image'].indexOf(type) < 0);
    try { el.focus(); } catch (e) {}
    if (isInput) { try { el.click(); } catch (e) {} return 'input'; }
    try { el.click(); } catch (e) {}
    return 'click';
  };
  return 'ok';
})();
"""
