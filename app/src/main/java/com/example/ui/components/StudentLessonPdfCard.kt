package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SupabaseStorageService
import com.example.model.LessonPdf
import com.example.ui.theme.DeepNavy
import com.example.ui.theme.EmeraldMint
import com.example.viewmodel.LocalPdfStatus

/**
 * بطاقة عرض وتنزيل ملف PDF الخاص بالدرس للطالب
 * تدعم أربع حالات: NotDownloaded، Downloading، Downloaded، Error
 */
@Composable
fun StudentLessonPdfCard(
    pdf: LessonPdf,
    status: LocalPdfStatus,
    onDownload: (LessonPdf) -> Unit,
    onCancel: (LessonPdf) -> Unit,
    onRetry: (LessonPdf) -> Unit,
    onDeleteLocal: (LessonPdf) -> Unit,
    onOpenPdf: ((LessonPdf) -> Unit)? = null,
    isOpening: Boolean = false,
    modifier: Modifier = Modifier
) {
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val formattedServerSize = remember(pdf.fileSizeBytes) {
        val size = pdf.fileSizeBytes
        if (size != null && size > 0) {
            SupabaseStorageService.formatFileSize(size)
        } else {
            "حجم غير محدد"
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("student_pdf_card_${pdf.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (status) {
                is LocalPdfStatus.Error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                is LocalPdfStatus.Downloaded -> EmeraldMint.copy(alpha = 0.08f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            1.dp,
            when (status) {
                is LocalPdfStatus.Error -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                is LocalPdfStatus.Downloaded -> EmeraldMint.copy(alpha = 0.4f)
                is LocalPdfStatus.Downloading -> DeepNavy.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // الصف العلوي: أيقونة PDF، العنوان، وتفاصيل الحجم / الشارة
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when (status) {
                                is LocalPdfStatus.Downloaded -> EmeraldMint.copy(alpha = 0.15f)
                                is LocalPdfStatus.Error -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                else -> Color(0xFFEF4444).copy(alpha = 0.12f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (status) {
                            is LocalPdfStatus.Downloaded -> Icons.Default.CheckCircle
                            is LocalPdfStatus.Error -> Icons.Default.ErrorOutline
                            else -> Icons.Default.PictureAsPdf
                        },
                        contentDescription = null,
                        tint = when (status) {
                            is LocalPdfStatus.Downloaded -> EmeraldMint
                            is LocalPdfStatus.Error -> MaterialTheme.colorScheme.error
                            else -> Color(0xFFEF4444)
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pdf.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = when (status) {
                            is LocalPdfStatus.Downloaded -> "الحجم المحفوظ: ${SupabaseStorageService.formatFileSize(status.fileSizeBytes)}"
                            else -> "الحجم: $formattedServerSize"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // الزر / الإجراء في الصف العلوي للحالات البسيطة (NotDownloaded و Downloaded)
                when (status) {
                    is LocalPdfStatus.NotDownloaded -> {
                        Button(
                            onClick = { onDownload(pdf) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DeepNavy,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("btn_download_pdf_${pdf.id}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDownward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "تنزيل",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                    is LocalPdfStatus.Downloaded -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // زر قراءة الملف الأصلي
                            Button(
                                onClick = { onOpenPdf?.invoke(pdf) },
                                enabled = !isOpening,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EmeraldMint,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .height(34.dp)
                                    .testTag("btn_open_pdf_${pdf.id}")
                            ) {
                                if (isOpening) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "فتح...",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.PictureAsPdf,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "قراءة الملف",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }

                            // شارة محفوظ ومشفّر
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = EmeraldMint.copy(alpha = 0.12f),
                                contentColor = EmeraldMint
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(11.dp),
                                        tint = EmeraldMint
                                    )
                                    Text(
                                        text = "مشفّر",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            }

                            // زر حذف النسخة المحلية
                            IconButton(
                                onClick = { showDeleteConfirmDialog = true },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("btn_delete_local_pdf_${pdf.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "حذف النسخة المحلية",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    else -> {}
                }
            }

            // القسم السفلي: مخصص لحالات جاري التنزيل أو الخطأ
            when (status) {
                is LocalPdfStatus.Downloading -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val downloadedStr = SupabaseStorageService.formatFileSize(status.bytesDownloaded)
                            val totalStr = if (status.totalBytes > 0) {
                                SupabaseStorageService.formatFileSize(status.totalBytes)
                            } else {
                                "..."
                            }
                            Text(
                                text = "جارٍ التنزيل: $downloadedStr / $totalStr (${status.progressPercentage}%)",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            // زر إلغاء التنزيل
                            TextButton(
                                onClick = { onCancel(pdf) },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("btn_cancel_download_pdf_${pdf.id}")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "إلغاء",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        if (status.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { status.progressPercentage / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = EmeraldMint,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = EmeraldMint,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
                is LocalPdfStatus.Error -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = status.message,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        FilledTonalButton(
                            onClick = { onRetry(pdf) },
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .height(30.dp)
                                .testTag("btn_retry_download_pdf_${pdf.id}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "إعادة المحاولة",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }

    // حوار تأكيد حذف النسخة المحلية المشفرة
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "تأكيد حذف النسخة المحلية",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            text = {
                Text(
                    text = "هل أنت متأكد من حذف النسخة المحملة محلياً لملف «${pdf.title}» من جهازك؟ لن يؤثر هذا على اشتراكك في المادة ويمكنك إعادة تنزيله في أي وقت.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteLocal(pdf)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("حذف النسخة المحلية")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("إلغاء")
                }
            }
        )
    }
}
