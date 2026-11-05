package com.example.ui.components

import android.net.Uri
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.LessonPdf
import com.example.ui.theme.DeepNavy
import com.example.ui.theme.EmeraldMint

/**
 * حوار إضافة وتعديل ملف PDF خاص بالدرس (واجهة المشرف)
 */
@Composable
fun AddEditLessonPdfDialog(
    pdf: LessonPdf? = null,
    selectedFileUri: Uri? = null,
    selectedFileName: String? = null,
    suggestedOrder: Int = 0,
    isOperating: Boolean = false,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onSaveAdd: (fileUri: Uri, title: String, orderIndex: Int, isPublished: Boolean) -> Unit,
    onSaveEdit: (pdfId: String, title: String, orderIndex: Int, isPublished: Boolean) -> Unit
) {
    val isEditMode = pdf != null

    // استخراج اسم مقترح للعنوان عند الإضافة من اسم الملف
    val initialTitle = remember(pdf, selectedFileName) {
        if (pdf != null) {
            pdf.title
        } else if (!selectedFileName.isNullOrBlank()) {
            selectedFileName.substringBeforeLast(".").replace('_', ' ').replace('-', ' ').trim()
        } else {
            ""
        }
    }

    var title by rememberSaveable(pdf?.id, selectedFileUri?.toString()) {
        mutableStateOf(initialTitle)
    }
    var orderIndexText by rememberSaveable(pdf?.id, selectedFileUri?.toString()) {
        mutableStateOf((pdf?.orderIndex ?: suggestedOrder).toString())
    }
    var isPublished by rememberSaveable(pdf?.id, selectedFileUri?.toString()) {
        mutableStateOf(pdf?.isPublished ?: false)
    }

    var validationError by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = {
            if (!isOperating) onDismiss()
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFEF4444).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = if (isEditMode) "تعديل بيانات ملف الـ PDF" else "إضافة ملف PDF للدرس",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // رسالة الخطأ الواردة من السيرفر أو التحقق
                val currentError = errorMessage ?: validationError
                if (!currentError.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = currentError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                // عرض بطاقة الملف المختار (في وضع الإضافة) أو إشعار الثبات (في وضع التعديل)
                if (!isEditMode) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = DeepNavy,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedFileName ?: "ملف PDF محدد",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (selectedFileUri != null) "جاهز للرفع السحابي" else "لم يتم اختيار ملف",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selectedFileUri != null) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "ملاحظة: تعديل الملف نفسه أو استبداله خارج نطاق هذه الشاشة؛ التعديل يشمل العنوان والترتيب وحالة النشر فقط.",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                // حقل عنوان الملف
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        if (it.isNotBlank()) validationError = null
                    },
                    label = { Text("عنوان الملف *") },
                    placeholder = { Text("مثال: ملخص الدرس، أوراق العمل...") },
                    enabled = !isOperating,
                    isError = validationError != null && title.isBlank(),
                    supportingText = {
                        Text("الاسم الذي سيظهر للطالب في قائمة مرفقات الدرس")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_pdf_title"),
                    shape = RoundedCornerShape(10.dp)
                )

                // حقل الترتيب التسلسلي
                OutlinedTextField(
                    value = orderIndexText,
                    onValueChange = { input ->
                        orderIndexText = input.filter { it.isDigit() }
                    },
                    label = { Text("ترتيب العرض (الرقم الترتيبي)") },
                    placeholder = { Text("0") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !isOperating,
                    supportingText = {
                        Text("يحدد أولوية ظهور هذا الملف مقارنة ببقية ملفات الدرس")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_pdf_order"),
                    shape = RoundedCornerShape(10.dp)
                )

                // خيار النشر للطلاب
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "نشر الملف للطلاب",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isPublished) "سيظهر الملف للطلاب المسجلين فوراً" else "سيبقى الملف كمسودة للمشرف فقط",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isPublished,
                            onCheckedChange = { isPublished = it },
                            enabled = !isOperating,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = EmeraldMint,
                                checkedTrackColor = DeepNavy
                            ),
                            modifier = Modifier.testTag("switch_pdf_publish")
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanTitle = title.trim()
                    if (cleanTitle.isBlank()) {
                        validationError = "عنوان ملف الـ PDF مطلوب ولا يمكن تركه فارغاً"
                        return@Button
                    }
                    val order = orderIndexText.toIntOrNull() ?: 0

                    if (!isEditMode) {
                        if (selectedFileUri == null) {
                            validationError = "يرجى اختيار ملف PDF صالح أولاً"
                            return@Button
                        }
                        onSaveAdd(selectedFileUri, cleanTitle, order, isPublished)
                    } else {
                        onSaveEdit(pdf!!.id, cleanTitle, order, isPublished)
                    }
                },
                enabled = !isOperating,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeepNavy,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("btn_save_pdf")
            ) {
                if (isOperating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("جاري الحفظ...", fontSize = 13.sp)
                } else {
                    Text(if (isEditMode) "تحديث البيانات" else "رفع وحفظ", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isOperating,
                modifier = Modifier.testTag("btn_cancel_pdf")
            ) {
                Text("إلغاء")
            }
        }
    )
}
