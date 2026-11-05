package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SupabaseStorageService
import com.example.model.LessonPdf
import com.example.ui.theme.EmeraldMint

/**
 * بطاقة عرض وإدارة ملف الـ PDF الخاص بالدرس للمشرف
 */
@Composable
fun AdminLessonPdfCard(
    pdf: LessonPdf,
    isOrphanPendingDbDelete: Boolean,
    isOperating: Boolean = false,
    onTogglePublish: (LessonPdf) -> Unit,
    onEdit: (LessonPdf) -> Unit,
    onDeleteWithStorage: (LessonPdf) -> Unit,
    onDeleteRecordOnly: (LessonPdf) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val formattedSize = remember(pdf.fileSizeBytes) {
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
            .testTag("admin_pdf_card_${pdf.id}"),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isOrphanPendingDbDelete) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            1.dp,
            if (isOrphanPendingDbDelete) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // الصف العلوي: الأيقونة، العنوان، وتفاصيل الحجم والترتيب
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isOrphanPendingDbDelete) {
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                } else {
                                    Color(0xFFEF4444).copy(alpha = 0.12f)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            tint = if (isOrphanPendingDbDelete) MaterialTheme.colorScheme.error else Color(0xFFEF4444),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pdf.title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("admin_pdf_title_${pdf.id}")
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = formattedSize,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                text = "•",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )

                            Text(
                                text = "الترتيب: ${pdf.orderIndex}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (pdf.isPublished) {
                                    Color(0xFF10B981).copy(alpha = 0.15f)
                                } else {
                                    Color(0xFFF59E0B).copy(alpha = 0.15f)
                                }
                            ) {
                                Text(
                                    text = if (pdf.isPublished) "منشور" else "مسودة",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = if (pdf.isPublished) Color(0xFF059669) else Color(0xFFD97706),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }

                // أزرار التحكم والإجراءات للمشرف
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (!isOrphanPendingDbDelete) {
                        IconButton(
                            onClick = { onTogglePublish(pdf) },
                            enabled = !isOperating,
                            modifier = Modifier
                                .size(30.dp)
                                .testTag("btn_toggle_publish_pdf_${pdf.id}")
                        ) {
                            Icon(
                                imageVector = if (pdf.isPublished) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (pdf.isPublished) "إلغاء النشر" else "نشر الملف",
                                tint = if (pdf.isPublished) EmeraldMint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = { onEdit(pdf) },
                            enabled = !isOperating,
                            modifier = Modifier
                                .size(30.dp)
                                .testTag("btn_edit_pdf_${pdf.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "تعديل بيانات الملف",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = { showDeleteConfirmDialog = true },
                            enabled = !isOperating,
                            modifier = Modifier
                                .size(30.dp)
                                .testTag("btn_delete_pdf_${pdf.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "حذف ملف الـ PDF",
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // في حال وجود سجل معلق (تم حذف التخزين وفشل حذف السجل)
            if (isOrphanPendingDbDelete) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "تم حذف الملف من التخزين السحابي مسبقاً، لكن قيد السجل لا يزال في قاعدة البيانات.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        FilledTonalButton(
                            onClick = { onDeleteRecordOnly(pdf) },
                            enabled = !isOperating,
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .align(Alignment.End)
                                .testTag("btn_retry_delete_record_${pdf.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("إعادة محاولة حذف السجل فقط", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // حوار تأكيد الحذف
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isOperating) showDeleteConfirmDialog = false
            },
            title = {
                Text(
                    text = "تأكيد حذف ملف PDF",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "هل أنت متأكد من رغبتك في حذف ملف «${pdf.title}»؟",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "تنبيه: سيتم حذف الملف الفعلي نهائياً من التخزين السحابي، بالإضافة إلى حذف كافة بياناته وسجله من قاعدة البيانات.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteWithStorage(pdf)
                    },
                    enabled = !isOperating,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444)),
                    modifier = Modifier.testTag("btn_confirm_delete_pdf_${pdf.id}")
                ) {
                    Text("حذف نهائي", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmDialog = false },
                    enabled = !isOperating
                ) {
                    Text("إلغاء")
                }
            }
        )
    }
}
