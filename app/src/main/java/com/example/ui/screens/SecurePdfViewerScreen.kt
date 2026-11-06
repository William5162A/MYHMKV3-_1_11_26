package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.PdfRenderEngine
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DeepNavy
import com.example.ui.theme.EmeraldMint
import com.example.ui.theme.LightBackground
import com.example.viewmodel.ActivePdfSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * شاشة عارض الـ PDF الأصلي الآمن داخل تطبيق مِداد (المرحلة 2-C-2).
 *
 * الخصائص الأمنية والمعمارية:
 * 1. تفعيل حماية FLAG_SECURE أثناء عرض المستند فقط واستعادة الحالة الأصلية بدقة عند الخروج.
 * 2. قراءة المستند من الملف المؤقت المفكوك دون إنشاء أي نسخ أخرى.
 * 3. عرض الصفحات بتمرير رأسي متتابع مع تحميل متأخر (Lazy Loading) عبر PdfRenderEngine.
 * 4. تكبير وتصغير للمستند كله بإصبعين أو بالنقر المزدوج، مع تمرير طبيعي وإعادة رسم بدقة أعلى عند التكبير.
 * 5. واجهة عربية RTL متكاملة مع الوضعين الفاتح والداكن وخطوط مِداد.
 */
private const val MAX_PDF_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurePdfViewerScreen(
    session: ActivePdfSession,
    onClose: () -> Unit,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 1. إدارة راية الحماية الأمنية FLAG_SECURE واستعادة الحالة السابقة بدقة
    DisposableEffect(context) {
        val activity = context.findActivity()
        val window = activity?.window
        val originalFlags = window?.attributes?.flags ?: 0
        val wasAlreadySecure = (originalFlags and WindowManager.LayoutParams.FLAG_SECURE) != 0

        if (!wasAlreadySecure) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        onDispose {
            if (!wasAlreadySecure) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    // زر الرجوع في النظام يغلق جلسة العرض بأمان
    BackHandler {
        onClose()
    }

    // 2. إدارة محرك الـ PDF
    val renderEngine = remember(session.decryptedTempFilePath) {
        try {
            PdfRenderEngine(session.decryptedTempFile)
        } catch (e: Exception) {
            null
        }
    }

    DisposableEffect(renderEngine) {
        onDispose {
            renderEngine?.close()
        }
    }

    if (renderEngine == null) {
        // حالة الخطأ في تهيئة الملف أو تلفه
        PdfErrorScreen(
            errorMessage = "تعذر فتح ملف الـ PDF للعرض أو أن تنسيق الملف غير مدعوم.",
            onClose = onClose,
            isDarkMode = isDarkMode
        )
        return
    }

    val pageCount = renderEngine.pageCount
    val listState = rememberLazyListState()

    // حالة التكبير للمستند كله (تكبير واحد لكل الصفحات كما في أي قارئ PDF)
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var zoomOffsetX by remember { mutableFloatStateOf(0f) }
    var settledScale by remember { mutableFloatStateOf(1f) }
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }

    // بعد توقف الأصابع نعيد رسم الصفحات بدقة أعلى ليبقى النص حادًا
    LaunchedEffect(zoomScale) {
        delay(350)
        settledScale = zoomScale
    }
    val renderScale = when {
        settledScale <= 1.3f -> 1f
        settledScale <= 2.2f -> 2f
        else -> 3f
    }

    // تطبيق تكبير/تصغير حول نقطة (cx, cy) مع تحريك (panX, panY) بوحدات الشاشة
    fun applyZoom(requestedScale: Float, cx: Float, cy: Float, panX: Float, panY: Float) {
        val oldScale = zoomScale
        val newScale = requestedScale.coerceIn(1f, MAX_PDF_ZOOM)
        val viewW = viewportWidthPx.toFloat()

        // الاتجاه الرأسي: نبقي النقطة تحت الأصابع ثابتة عبر تمرير القائمة نفسها
        val deltaY = (cy / oldScale) - (cy / newScale) - (panY / newScale)
        if (deltaY != 0f) {
            listState.dispatchRawDelta(deltaY)
        }

        // الاتجاه الأفقي: إزاحة مقيّدة بحدود الصفحة
        val rawOffsetX = cx - (cx - zoomOffsetX) * (newScale / oldScale) + panX
        zoomOffsetX = rawOffsetX.coerceIn(viewW * (1f - newScale), 0f)
        zoomScale = newScale
    }

    // تتبع رقم الصفحة الحالية (1-based)
    val currentPageIndex by remember {
        derivedStateOf {
            (listState.firstVisibleItemIndex + 1).coerceIn(1, pageCount)
        }
    }

    var showJumpDialog by remember { mutableStateOf(false) }
    var showSecurityInfoDialog by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .testTag("secure_pdf_viewer_screen"),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = session.pdf.title.ifBlank { "مستند الـ PDF" },
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "مستند أصلي محمي",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    color = EmeraldMint
                                )
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.testTag("btn_close_pdf_viewer")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "رجوع وإغلاق الملف"
                            )
                        }
                    },
                    actions = {
                        // زر معلومات الأمان
                        IconButton(
                            onClick = { showSecurityInfoDialog = true },
                            modifier = Modifier.testTag("btn_pdf_security_info")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "معلومات الحماية",
                                tint = EmeraldMint,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // زر الانتقال المباشر لصفحة
                        IconButton(
                            onClick = { showJumpDialog = true },
                            modifier = Modifier.testTag("btn_jump_to_page")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bookmark,
                                contentDescription = "الانتقال لصفحة"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (isDarkMode) DeepNavy else MaterialTheme.colorScheme.surface,
                        titleContentColor = if (isDarkMode) Color.White else MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = if (isDarkMode) Color.White else MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = if (isDarkMode) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            containerColor = if (isDarkMode) DarkBackground else LightBackground
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (pageCount == 0) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "المستند لا يحتوي على أي صفحات صالحة للعرض",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // ارتفاع إضافي أسفل القائمة كي يمكن الوصول لنهاية آخر صفحة عند التكبير
                    val extraBottomPadding = with(LocalDensity.current) {
                        (viewportHeightPx * (1f - 1f / zoomScale)).coerceAtLeast(0f).toDp()
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .onSizeChanged { size ->
                                viewportWidthPx = size.width
                                viewportHeightPx = size.height
                            }
                            // النقر المزدوج: تكبير حول مكان النقر أو العودة للحجم الطبيعي
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = { tapPosition ->
                                        coroutineScope.launch {
                                            val from = zoomScale
                                            val to = if (from > 1.05f) 1f else DOUBLE_TAP_ZOOM
                                            animate(
                                                initialValue = from,
                                                targetValue = to,
                                                animationSpec = tween(durationMillis = 220)
                                            ) { value, _ ->
                                                applyZoom(value, tapPosition.x, tapPosition.y, 0f, 0f)
                                            }
                                        }
                                    }
                                )
                            }
                            // التكبير بإصبعين، وتحريك أفقي بإصبع واحد عند التكبير.
                            // الإصبع الواحد لا يُستهلك فيبقى التمرير الرأسي أصليًا من القائمة.
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val pressedCount = event.changes.count { it.pressed }
                                        if (pressedCount >= 2) {
                                            val zoom = event.calculateZoom()
                                            val pan = event.calculatePan()
                                            val centroid = event.calculateCentroid()
                                            if (zoom != 1f || pan != Offset.Zero) {
                                                applyZoom(zoomScale * zoom, centroid.x, centroid.y, pan.x, pan.y)
                                            }
                                            event.changes.forEach { change ->
                                                if (change.positionChanged()) change.consume()
                                            }
                                        } else if (pressedCount == 1 && zoomScale > 1.001f) {
                                            val change = event.changes.firstOrNull { it.pressed }
                                            if (change != null) {
                                                val dx = change.position.x - change.previousPosition.x
                                                if (dx != 0f) {
                                                    applyZoom(zoomScale, 0f, 0f, dx, 0f)
                                                }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("pdf_pages_lazy_column")
                                .graphicsLayer {
                                    transformOrigin = TransformOrigin(0f, 0f)
                                    scaleX = zoomScale
                                    scaleY = zoomScale
                                    translationX = zoomOffsetX
                                },
                            contentPadding = PaddingValues(
                                start = 8.dp,
                                end = 8.dp,
                                top = 12.dp,
                                bottom = 12.dp + extraBottomPadding
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            items(
                                count = pageCount,
                                key = { pageIdx -> "pdf_page_$pageIdx" }
                            ) { pageIndex ->
                                PdfPageCard(
                                    pageIndex = pageIndex,
                                    renderEngine = renderEngine,
                                    renderScale = renderScale,
                                    isDarkMode = isDarkMode,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("pdf_page_item_$pageIndex")
                                )
                            }
                        }
                    }

                    // مؤشر رقم الصفحة العائم (Pill Indicator)
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                            .shadow(6.dp, CircleShape)
                            .testTag("pdf_page_indicator_pill"),
                        shape = CircleShape,
                        color = if (isDarkMode) DeepNavy.copy(alpha = 0.92f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        border = BorderStroke(1.dp, EmeraldMint.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(EmeraldMint)
                            )
                            Text(
                                text = "صفحة $currentPageIndex من $pageCount",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp
                                ),
                                color = if (isDarkMode) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }

    // نافذة الانتقال المباشر إلى صفحة
    if (showJumpDialog) {
        JumpToPageDialog(
            totalPages = pageCount,
            currentPage = currentPageIndex,
            onDismiss = { showJumpDialog = false },
            onConfirm = { targetPage ->
                showJumpDialog = false
                val targetIndex = (targetPage - 1).coerceIn(0, pageCount - 1)
                coroutineScope.launch {
                    listState.scrollToItem(targetIndex)
                }
            }
        )
    }

    // نافذة معلومات الحماية والأمان
    if (showSecurityInfoDialog) {
        SecurityInfoDialog(onDismiss = { showSecurityInfoDialog = false })
    }
}

/**
 * مكون البطاقة الخاصة برسم الصفحة الواحدة.
 * التكبير والتحريك يُدار على مستوى المستند كله (في SecurePdfViewerScreen)،
 * وهنا نعيد رسم الصفحة بدقة أعلى فقط عندما يستقر التكبير.
 */
@Composable
fun PdfPageCard(
    pageIndex: Int,
    renderEngine: PdfRenderEngine,
    renderScale: Float,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier
) {
    var containerWidthPx by remember { mutableIntStateOf(0) }
    var aspectRatio by remember { mutableFloatStateOf(0.707f) }

    var renderedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    // جلب النسبة الباعية للصفحة
    LaunchedEffect(pageIndex) {
        aspectRatio = renderEngine.getPageAspectRatio(pageIndex)
    }

    // رسم الصفحة عندما يتوفر عرض الحاوية (وإعادة الرسم بدقة أعلى عند التكبير)
    LaunchedEffect(pageIndex, containerWidthPx, renderScale) {
        if (containerWidthPx <= 0) return@LaunchedEffect

        val targetWidth = (containerWidthPx * renderScale).toInt()

        // فحص الكاش أولاً
        val cached = renderEngine.getCachedPage(pageIndex, targetWidth)
        if (cached != null && !cached.isRecycled) {
            renderedBitmap = cached
            isLoading = false
            isError = false
            return@LaunchedEffect
        }

        // نُبقي الصورة السابقة ظاهرة أثناء رسم النسخة الأوضح
        isLoading = true
        isError = false
        val result = renderEngine.renderPage(pageIndex, targetWidth)
        if (result.isSuccess) {
            renderedBitmap = result.getOrNull()
            isLoading = false
            isError = false
        } else {
            isLoading = false
            if (renderedBitmap == null) isError = true
        }
    }

    Surface(
        modifier = modifier
            .onSizeChanged { size ->
                if (size.width > 0 && size.width != containerWidthPx) {
                    containerWidthPx = size.width
                }
            }
            .shadow(4.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp)),
        color = Color.White,
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = renderedBitmap
            if (bitmap != null && !bitmap.isRecycled) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "صفحة رقم ${pageIndex + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (isLoading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = EmeraldMint,
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "جاري معالجة الصفحة ${pageIndex + 1}...",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = Color.DarkGray
                    )
                }
            } else if (isError) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "تعذر رسم الصفحة ${pageIndex + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * نافذة الانتقال المباشر لرقم صفحة محددة
 */
@Composable
fun JumpToPageDialog(
    totalPages: Int,
    currentPage: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var inputText by remember { mutableStateOf(currentPage.toString()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "انتقال إلى صفحة",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column {
                Text(
                    text = "أدخل رقم الصفحة بين 1 و $totalPages:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = inputText,
                    onValueChange = {
                        inputText = it.filter { char -> char.isDigit() }
                        errorMessage = null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val parsed = inputText.toIntOrNull()
                            if (parsed != null && parsed in 1..totalPages) {
                                onConfirm(parsed)
                            } else {
                                errorMessage = "يرجى إدخال رقم بين 1 و $totalPages"
                            }
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_jump_page_number"),
                    isError = errorMessage != null,
                    supportingText = {
                        if (errorMessage != null) {
                            Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error)
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = inputText.toIntOrNull()
                    if (parsed != null && parsed in 1..totalPages) {
                        onConfirm(parsed)
                    } else {
                        errorMessage = "يرجى إدخال رقم بين 1 و $totalPages"
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldMint),
                modifier = Modifier.testTag("btn_confirm_jump_page")
            ) {
                Text("انتقال", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

/**
 * نافذة التوضيح الأمني لحدود الحماية
 */
@Composable
fun SecurityInfoDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = EmeraldMint,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = "حماية المحتوى التعليمي",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "• المحتوى محمي بنظام التشفير الآمن AES-256 مع حظر تصوير وتسجيل الشاشة (FLAG_SECURE) داخل هذا العارض.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• تنبيه: تحظر هذه الراية أدوات التقاط الشاشة النظامية، لكنها لا تمنع التصوير الخارجي بكاميرا أخرى أو التجاوز في الأجهزة المعدلة جذرياً (Rooted).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("حسناً", fontWeight = FontWeight.Bold)
            }
        }
    )
}

/**
 * شاشة الخطأ عند فشل فتح ملف الـ PDF
 */
@Composable
fun PdfErrorScreen(
    errorMessage: String,
    onClose: () -> Unit,
    isDarkMode: Boolean
) {
    Scaffold(
        containerColor = if (isDarkMode) DarkBackground else LightBackground
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "تعذر فتح المستند",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (isDarkMode) Color.White else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldMint),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("العودة", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * دالة مساعدة لاستخراج كائن Activity من الـ Context عبر فك التفاف ContextWrapper
 */
fun Context.findActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) {
            return currentContext
        }
        currentContext = currentContext.baseContext
    }
    return null
}
