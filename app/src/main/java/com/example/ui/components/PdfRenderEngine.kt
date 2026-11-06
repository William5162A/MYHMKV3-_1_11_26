package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * محرك عرض ملفات الـ PDF الأصلي في تطبيق مِداد (المرحلة 2-C-2).
 *
 * المسؤوليات:
 * 1. إدارة دورة حياة ParcelFileDescriptor و Android PdfRenderer.
 * 2. قراءة عدد الصفحات وأبعاد كل صفحة بدقة.
 * 3. قفل حرج عبر Mutex لضمان تنفيذ عمليات PdfRenderer بشكل متسلسل وخارج Main Thread،
 *    حيث يمنع نظام أندرويد فتح أكثر من صفحة في وقت واحد ويرمي IllegalStateException.
 * 4. إدارة ذاكرة الكاش للصور Bitmap باستخدام LruCache محدود بالبايتات الفعلية وليس عدد الصفحات.
 * 5. إغلاق الموارد بأمان عند الانتهاء دون أي تسريب في الذاكرة أو واصفات الملفات.
 */
class PdfRenderEngine(
    private val pdfFile: File
) {
    companion object {
        private const val TAG = "PdfRenderEngine"
        // أقصى عرض للصورة المرسومة (يسمح بوضوح جيد عند التكبير دون استهلاك مفرط للذاكرة)
        private const val MAX_RENDER_WIDTH_PX = 2400
        // نسبة الذاكرة المخصصة لكاش صفحات الـ PDF (تقريباً 20% من الذاكرة المتاحة بحدود آمنة)
        private val MAX_CACHE_MEMORY_BYTES = run {
            val maxMemory = Runtime.getRuntime().maxMemory()
            (maxMemory * 0.20).toLong().coerceIn(25L * 1024 * 1024, 75L * 1024 * 1024)
        }
    }

    private val rendererMutex = Mutex()
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    @Volatile
    private var isClosed = false

    val pageCount: Int

    // كاش لحفظ النسبة الباعية (العرض/الارتفاع) لكل صفحة لمنع اهتزاز القائمة أثناء التمرير
    private val pageAspectRatios = mutableMapOf<Int, Float>()

    // ذاكرة كاش محددة بالبايتات لصور الصفحات المرسومة
    // المفتاح = (رقم الصفحة + عرض الرسم) كي لا تختلط الصورة العادية بالصورة عالية الدقة عند التكبير
    private val bitmapCache = object : LruCache<Long, Bitmap>((MAX_CACHE_MEMORY_BYTES / 1024).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    private fun cacheKey(pageIndex: Int, width: Int): Long =
        (pageIndex.toLong() shl 32) or (width.toLong() and 0xFFFFFFFFL)

    private fun clampWidth(targetWidth: Int): Int = targetWidth.coerceIn(200, MAX_RENDER_WIDTH_PX)

    init {
        if (!pdfFile.exists() || !pdfFile.isFile) {
            throw IOException("ملف الـ PDF غير موجود أو تالف: ${pdfFile.absolutePath}")
        }

        val fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            ?: throw IOException("تعذر فتح ParcelFileDescriptor للملف: ${pdfFile.name}")

        try {
            val pdfRenderer = PdfRenderer(fileDescriptor)
            this.pfd = fileDescriptor
            this.renderer = pdfRenderer
            this.pageCount = pdfRenderer.pageCount

            // استقراء مبدئي للنسبة الباعية للصفحة الأولى كنموذج افتراضي
            if (pageCount > 0) {
                try {
                    val firstPage = pdfRenderer.openPage(0)
                    val ratio = firstPage.width.toFloat() / firstPage.height.toFloat()
                    pageAspectRatios[0] = ratio
                    firstPage.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read initial page aspect ratio", e)
                }
            }
        } catch (e: Exception) {
            try {
                fileDescriptor.close()
            } catch (_: Exception) {}
            throw IOException("تعذر تهيئة PdfRenderer للملف: ${e.message}", e)
        }
    }

    /**
     * استرجاع النسبة الباعية (عرض / ارتفاع) للصفحة
     */
    suspend fun getPageAspectRatio(pageIndex: Int): Float {
        if (pageIndex !in 0 until pageCount) return 0.707f // النسبة القياسية لـ A4 كقيمة احتياطية

        synchronized(pageAspectRatios) {
            pageAspectRatios[pageIndex]?.let { return it }
        }

        return rendererMutex.withLock {
            if (isClosed) return@withLock 0.707f
            try {
                val currentRenderer = renderer ?: return@withLock 0.707f
                val page = currentRenderer.openPage(pageIndex)
                val ratio = page.width.toFloat() / page.height.toFloat()
                page.close()
                synchronized(pageAspectRatios) {
                    pageAspectRatios[pageIndex] = ratio
                }
                ratio
            } catch (e: Exception) {
                Log.w(TAG, "Failed to inspect page $pageIndex aspect ratio", e)
                0.707f
            }
        }
    }

    /**
     * جلب الصفحة المرسومة من الكاش إن وجدت فوراً
     */
    fun getCachedPage(pageIndex: Int, targetWidth: Int): Bitmap? {
        if (isClosed) return null
        return bitmapCache.get(cacheKey(pageIndex, clampWidth(targetWidth)))
    }

    /**
     * رسم صفحة محددة بحجم مستهدف متناسب مع الشاشة، مع قفل صارم يمنع فتح صفحتين معاً
     * ويجري الرسم خارج خيط الواجهة Main Thread
     */
    suspend fun renderPage(pageIndex: Int, targetWidth: Int): Result<Bitmap> {
        if (pageIndex !in 0 until pageCount) {
            return Result.failure(IllegalArgumentException("رقم الصفحة $pageIndex غير صالح (إجمالي الصفحات: $pageCount)"))
        }

        if (isClosed) {
            return Result.failure(IllegalStateException("تم إغلاق محرك الـ PDF"))
        }

        val finalWidth = clampWidth(targetWidth)
        val key = cacheKey(pageIndex, finalWidth)

        // 1. التحقق من الكاش في الذاكرة
        bitmapCache.get(key)?.let { cached ->
            if (!cached.isRecycled) {
                return Result.success(cached)
            }
        }

        // 2. الدخول إلى القسم الحرج لرسم الصفحة عبر Mutex
        return rendererMutex.withLock {
            if (isClosed) {
                return@withLock Result.failure(IllegalStateException("تم إغلاق المحرك أثناء الانتظار"))
            }

            // فحص ثانٍ للكاش بعد كسب القفل
            bitmapCache.get(key)?.let { cached ->
                if (!cached.isRecycled) {
                    return@withLock Result.success(cached)
                }
            }

            withContext(Dispatchers.Default) {
                try {
                    val currentRenderer = renderer
                        ?: return@withContext Result.failure(IllegalStateException("PdfRenderer غير متاح"))

                    // فتح الصفحة بدقة
                    val page = currentRenderer.openPage(pageIndex)
                    try {
                        val srcWidth = page.width
                        val srcHeight = page.height

                        synchronized(pageAspectRatios) {
                            pageAspectRatios[pageIndex] = srcWidth.toFloat() / srcHeight.toFloat()
                        }

                        val finalHeight = ((finalWidth.toFloat() / srcWidth) * srcHeight).toInt().coerceAtLeast(100)

                        // تخصيص Bitmap مع خلفية بيضاء صافية (ضروري لأن PdfRenderer شفاف افتراضياً)
                        val bitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)

                        // تنفيذ عملية الرسم
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        // إغلاق الصفحة فوراً قبل وضعها في الكاش أو إطلاق القفل لصفحة أخرى
                        page.close()

                        if (!isClosed) {
                            bitmapCache.put(key, bitmap)
                            Result.success(bitmap)
                        } else {
                            bitmap.recycle()
                            Result.failure(IllegalStateException("تم إغلاق المحرك أثناء الرسم"))
                        }
                    } catch (e: Exception) {
                        try {
                            page.close()
                        } catch (_: Exception) {}
                        throw e
                    }
                } catch (oom: OutOfMemoryError) {
                    Log.e(TAG, "OutOfMemoryError while rendering page $pageIndex, clearing cache", oom)
                    bitmapCache.evictAll()
                    System.gc()
                    Result.failure(Exception("نفدت الذاكرة أثناء معالجة الصفحة $pageIndex"))
                } catch (e: Exception) {
                    Log.e(TAG, "Error rendering page $pageIndex", e)
                    Result.failure(e)
                }
            }
        }
    }

    /**
     * تحميل النسب الباعية لكل الصفحات مرة واحدة لتثبيت ارتفاعات القائمة (اختياري)
     */
    suspend fun preloadAspectRatios() {
        val missing = synchronized(pageAspectRatios) {
            (0 until pageCount).filter { !pageAspectRatios.containsKey(it) }
        }
        if (missing.isEmpty()) return

        rendererMutex.withLock {
            val r = renderer ?: return@withLock
            for (i in missing) {
                if (isClosed) break
                try {
                    val p = r.openPage(i)
                    val ratio = p.width.toFloat() / p.height.toFloat()
                    p.close()
                    synchronized(pageAspectRatios) { pageAspectRatios[i] = ratio }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * إغلاق كافة الموارد المفتوحة ومسح الكاش بأمان متكرر (Idempotent)
     */
    fun close() {
        if (isClosed) return
        isClosed = true

        try {
            bitmapCache.evictAll()
        } catch (_: Exception) {}

        try {
            renderer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing PdfRenderer", e)
        } finally {
            renderer = null
        }

        try {
            pfd?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ParcelFileDescriptor", e)
        } finally {
            pfd = null
        } 
    }
}

