package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.example.data.SupabaseStorageService
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.theme.CairoFontFamily
import com.example.ui.theme.DeepNavy
import com.example.ui.theme.EmeraldMint
import com.example.ui.theme.ReemKufiFontFamily
import com.example.viewmodel.AppUiState
import com.example.viewmodel.AppViewModel
import com.example.viewmodel.UserRole

// ==============================================================================
// Icon selector matching subject types
// ==============================================================================
fun iconForSubject(name: String): ImageVector {
    return when {
        name.contains("رياضيات") || name.contains("نمذجة") || name.contains("إشارة") -> Icons.Default.Functions
        name.contains("لغة") || name.contains("اللغة") -> Icons.Default.Language
        name.contains("كيمياء") -> Icons.Default.Science
        name.contains("فيزياء") || name.contains("بصريات") -> Icons.Default.Lightbulb
        name.contains("برمجة") || name.contains("حاسوب") || name.contains("حواسيب") || name.contains("متحكمات") -> Icons.Default.Memory
        name.contains("شبكات") || name.contains("اتصال") -> Icons.Default.Lan
        name.contains("الاتصالات") -> Icons.Default.CellTower
        name.contains("كهربائية") || name.contains("القيادة") || name.contains("إلكترون") -> Icons.Default.Bolt
        name.contains("حرارية") || name.contains("تدفئة") || name.contains("طاقة") -> Icons.Default.LocalFireDepartment
        name.contains("قياس") || name.contains("حساسات") || name.contains("تحكم") -> Icons.Default.Speed
        name.contains("رسم") || name.contains("تصميم") -> Icons.Default.Brush
        name.contains("روبوت") || name.contains("ذكاء") || name.contains("PLC") || name.contains("أتمتة") -> Icons.Default.SmartToy
        name.contains("صور") -> Icons.Default.Visibility
        name.contains("آلات") || name.contains("ميكانيك") || name.contains("إنتاج") || name.contains("مواد") || name.contains("معادن") -> Icons.Default.Settings
        else -> Icons.AutoMirrored.Filled.MenuBook
    }
}

// ==============================================================================
// 1. الرئيسية (Home Section)
// ==============================================================================
@Composable
fun HomeScreen(
    state: AppUiState,
    onNavigateToBrowse: () -> Unit,
    onNavigateToMyCourses: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onSelectSubject: (String) -> Unit
) {
    val baseSubjects = remember(state.role, state.subjects) {
        if (state.role == UserRole.ADMIN) {
            state.subjects
        } else {
            state.subjects.filter { it.isPublished }
        }
    }
    val enrolledSubjects = remember(baseSubjects, state.enrolledSubjectIds) {
        baseSubjects.filter { state.enrolledSubjectIds.contains(it.id) }
    }
    val recommendedSubjects = remember(baseSubjects, state.academicYear, state.semester, state.enrolledSubjectIds) {
        baseSubjects.filter {
            it.academicYear == state.academicYear && it.semester == state.semester && !state.enrolledSubjectIds.contains(it.id)
        }
    }
    val displayName = if (state.role == UserRole.ADMIN) "المشرف العام" else "طالب"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("screen_home"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --------------------------------------------------------------------------
        // 1. Hero Welcome Card (Preserved completely as requested)
        // --------------------------------------------------------------------------
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFF1E293B),
                                    Color(0xFF111A3A),
                                    Color(0xFF0B132B)
                                )
                            )
                        )
                        .padding(22.dp)
                ) {
                    Text(
                        text = "مِداد",
                        fontSize = 96.sp,
                        fontFamily = ReemKufiFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.045f),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = (-12).dp, y = 24.dp)
                    )

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (state.role != UserRole.ADMIN) {
                                Text(
                                    text = "${CurriculumData.getYearName(state.academicYear)} · ${CurriculumData.getSemesterName(state.semester)}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = CairoFontFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    ),
                                    color = Color(0xFF94A3B8)
                                )
                            } else {
                                Text(
                                    text = "لوحة تحكم المشرف",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = CairoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    ),
                                    color = EmeraldMint
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = EmeraldMint.copy(alpha = 0.18f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldMint.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    text = "جامعة حمص",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = CairoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    ),
                                    color = EmeraldMint,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "أهلًا بعودتك، $displayName",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = ReemKufiFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 28.sp,
                                color = Color.White
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = if (state.role == UserRole.ADMIN)
                                "أنت في وضع المشرف العام: يمكنك نشر المقررات وإضافة العناوين ورفع ملفات الـ PDF."
                            else
                                "رحلتك نحو التفوق الأكاديمي تبدأ بخطوة صغيرة اليوم.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.5.sp,
                                lineHeight = 21.sp
                            ),
                            color = Color(0xFF94A3B8)
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onNavigateToBrowse,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EmeraldMint,
                                    contentColor = DeepNavy
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                            ) {
                                Text(
                                    text = if (state.role == UserRole.ADMIN) "إدارة وتصفح المواد" else "تصفح المواد المنشورة",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            if (state.role != UserRole.ADMIN) {
                                OutlinedButton(
                                    onClick = onNavigateToMyCourses,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        Color(0xFF94A3B8).copy(alpha = 0.35f)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp)
                                ) {
                                    Text(
                                        text = "موادي الدراسية",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // --------------------------------------------------------------------------
        // 2. The 4 Stats Cards Grid (2x2):
        // Card 0 (Top-Right in RTL): "المواد المشتراة" (Purchased subjects)
        // Card 1 (Top-Left in RTL): "الدروس المنجزة" (Completed topics ONLY from purchased subjects / Total topics of purchased subjects)
        // Card 2 (Bottom-Right in RTL): "نسبة إنهاء الدروس" (Progress percentage of purchased subjects)
        // Card 3 (Bottom-Left in RTL): "السنة الدراسية والفصل الحالي" (Academic year & semester)
        // --------------------------------------------------------------------------
        item {
            if (state.role == UserRole.ADMIN) {
                // ======================================================================
                // Admin 4 Stats Cards Grid (2x2):
                // Card 0 (Top-Right in RTL): عدد المواد المضافة للطلاب
                // Card 1 (Top-Left in RTL): عدد المواد الكلية اي 60
                // Card 2 (Bottom-Right in RTL): نسبة اكمال اضافة المواد
                // Card 3 (Bottom-Left in RTL): السنة التي فيها اقل عدد من المواد المضافة
                // ======================================================================
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Card 0 (Top-Right in RTL): عدد المواد المضافة للطلاب
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onNavigateToBrowse() }
                                .testTag("home_stat_admin_added_subjects"),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = EmeraldMint),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = null,
                                    tint = DeepNavy.copy(alpha = 0.75f),
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "${state.addedSubjectsCount}",
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 24.sp
                                    ),
                                    color = DeepNavy
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "عدد المواد المضافة للطلاب",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = DeepNavy.copy(alpha = 0.85f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Card 1 (Top-Left in RTL): عدد المواد الكلية اي 60
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onNavigateToBrowse() }
                                .testTag("home_stat_admin_total_subjects"),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                    contentDescription = null,
                                    tint = EmeraldMint,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "${state.totalSubjectsCount}",
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 24.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "عدد المواد الكلية",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Card 2 (Bottom-Right in RTL): نسبة اكمال اضافة المواد
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onNavigateToBrowse() }
                                .testTag("home_stat_admin_completion_percentage"),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                                    contentDescription = null,
                                    tint = EmeraldMint,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "${state.addedCompletionPercentage}%",
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 24.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "نسبة اكمال اضافة المواد",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Card 3 (Bottom-Left in RTL): السنة التي فيها اقل عدد من المواد المضافة
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onNavigateToBrowse() }
                                .testTag("home_stat_admin_lowest_year"),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.HourglassBottom,
                                    contentDescription = null,
                                    tint = Color(0xFFD97706),
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = CurriculumData.getYearName(state.yearWithLowestAddedSubjects.year),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "أقل سنة في المواد المضافة (${state.yearWithLowestAddedSubjects.count})",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            } else {
                // ======================================================================
                // Student 4 Stats Cards Grid (2x2):
                // Card 0 (Top-Right in RTL): "المواد المشتراة" (Purchased subjects)
                // Card 1 (Top-Left in RTL): "الدروس المنجزة"
                // Card 2 (Bottom-Right in RTL): "نسبة إنهاء الدروس"
                // Card 3 (Bottom-Left in RTL): "السنة الدراسية والفصل الحالي"
                // ======================================================================
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Card 0 (Top-Right): Purchased Subjects Count (المواد المشتراة)
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onNavigateToMyCourses() }
                                .testTag("home_stat_purchased_subjects"),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = EmeraldMint),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = null,
                                    tint = DeepNavy.copy(alpha = 0.75f),
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "${enrolledSubjects.size}",
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 24.sp
                                    ),
                                    color = DeepNavy
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "المواد المشتراة",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = DeepNavy.copy(alpha = 0.85f)
                                )
                            }
                        }

                        // Card 1 (Top-Left): Completed topics ONLY from purchased subjects
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onNavigateToMyCourses() }
                                .testTag("home_stat_completed_lessons"),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = EmeraldMint,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "${state.purchasedCompletedTopicsCount} / ${state.purchasedTotalTopicsCount}",
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 20.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "الدروس المنجزة",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Card 2 (Bottom-Right): Completion Percentage of purchased subjects
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onNavigateToMyCourses() }
                                .testTag("home_stat_progress_percentage"),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                                    contentDescription = null,
                                    tint = EmeraldMint,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "${state.purchasedProgressPercentage}%",
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 24.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "نسبة إنهاء الدروس",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Card 3 (Bottom-Left): Academic Year & Current Semester
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onNavigateToProfile() }
                                .testTag("home_stat_academic_info"),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.School,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = CurriculumData.getYearName(state.academicYear),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = CurriculumData.getSemesterName(state.semester),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = EmeraldMint
                                )
                            }
                        }
                    }
                }
            }
        }

        // --------------------------------------------------------------------------
        // 3. Enrolled & Recommended Subjects Sections (STUDENTS ONLY):
        // For Admin: All sections after the 4 cards are removed as requested.
        // --------------------------------------------------------------------------
        if (state.role != UserRole.ADMIN) {
            if (enrolledSubjects.isNotEmpty()) {
                item {
                    SectionTitle(
                        kicker = "من حيث توقفت",
                        title = "تابع الدراسة"
                    )
                }

                items(enrolledSubjects, key = { it.id }) { subject ->
                    SubjectCard(
                        subject = subject,
                        isEnrolled = true,
                        role = state.role,
                        isPublished = state.addedSubjectIds.contains(subject.id),
                        onActionClick = { onSelectSubject(subject.id) }
                    )
                }
            }

            item {
                SectionTitle(
                    kicker = "حسب سنتك وفصلك (${CurriculumData.getYearName(state.academicYear)} · ${CurriculumData.getSemesterName(state.semester)})",
                    title = "مواد مقترحة لدراستها",
                    actionLabel = "تغيير الفصل",
                    onActionClick = onNavigateToProfile
                )
            }

            if (recommendedSubjects.isNotEmpty()) {
                items(recommendedSubjects.take(6), key = { it.id }) { subject ->
                    SubjectCard(
                        subject = subject,
                        isEnrolled = false,
                        role = state.role,
                        isPublished = state.addedSubjectIds.contains(subject.id),
                        onActionClick = { onNavigateToBrowse() }
                    )
                }
            } else {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                        )
                    ) {
                        Text(
                            text = "لم يتم نشر مواد إضافية في هذا الفصل بعد.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

// ==============================================================================
// SectionTitle matching Bits.tsx
// ==============================================================================
@Composable
fun SectionTitle(
    kicker: String? = null,
    title: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (!kicker.isNullOrEmpty()) {
                Text(
                    text = kicker,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = ReemKufiFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        if (!actionLabel.isNullOrEmpty() && onActionClick != null) {
            Text(
                text = actionLabel,
                fontSize = 13.sp,
                fontFamily = CairoFontFamily,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onActionClick() }
                    .padding(4.dp)
            )
        }
    }
}

// ==============================================================================
// SubjectCard
// ==============================================================================
@Composable
fun SubjectCard(
    subject: Subject,
    isEnrolled: Boolean,
    role: UserRole,
    isPublished: Boolean = false,
    onActionClick: () -> Unit,
    onTogglePublish: (() -> Unit)? = null
) {
    val icon = remember(subject.name) { iconForSubject(subject.name) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("subject_card_${subject.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(EmeraldMint.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = EmeraldMint,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Text(
                    text = String.format("%02d", subject.year),
                    fontFamily = ReemKufiFontFamily,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = subject.name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "${CurriculumData.getYearName(subject.year)} · ${CurriculumData.getSemesterName(subject.semester)}",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Dedicated Red/Green Toggle for Admin
            if (role == UserRole.ADMIN) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "ظهور المادة للطلاب:",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Surface(
                        onClick = { onTogglePublish?.invoke() },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isPublished) Color(0xFF10B981) else Color(0xFFEF4444),
                        contentColor = Color.White,
                        modifier = Modifier.testTag("toggle_subject_${subject.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (isPublished) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Text(
                                text = if (isPublished) "مضافة للطلاب (مفعلة)" else "غير مضافة (اضغط للتفعيل)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp
                                ),
                                color = Color.White
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (role == UserRole.ADMIN) {
                    Text(
                        text = if (isPublished) "مدرجة في قائمة الطلاب" else "قيد الإعداد (مسودة)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (isPublished) Color(0xFF059669) else Color(0xFFDC2626),
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                } else if (isEnrolled) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = EmeraldMint,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "مادة مشتراة",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            ),
                            color = EmeraldMint
                        )
                    }
                } else {
                    Text(
                        text = "مقرر دراسي معتمد",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    onClick = onActionClick,
                    shape = RoundedCornerShape(8.dp),
                    color = DeepNavy,
                    contentColor = Color.White
                ) {
                    Text(
                        text = if (role == UserRole.ADMIN) "إدارة المحتوى والشرح" else if (isEnrolled) "متابعة الدراسة" else "تصفح المادة",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

// ==============================================================================
// 2. موادي / المواد المضافة (My Courses / Added Courses Section)
// ==============================================================================
@Composable
fun MyCoursesScreen(
    state: AppUiState,
    onSelectSubject: (String) -> Unit,
    onNavigateToBrowse: () -> Unit,
    onTogglePublishSubject: ((String) -> Unit)? = null
) {
    val isAdmin = state.role == UserRole.ADMIN
    val baseSubjects = remember(isAdmin, state.subjects) {
        if (isAdmin) state.subjects else state.subjects.filter { it.isPublished }
    }
    val subjectList = remember(isAdmin, baseSubjects, state.enrolledSubjectIds, state.addedSubjectIds) {
        if (isAdmin) {
            baseSubjects.filter { it.isPublished || state.addedSubjectIds.contains(it.id) }
        } else {
            baseSubjects.filter { state.enrolledSubjectIds.contains(it.id) }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("screen_my_courses"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                Text(
                    text = if (isAdmin) "لوحة تحكم المشرف · جامعة حمص" else "مكتبتك الأكاديمية",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isAdmin) "المواد المضافة" else "موادي الدراسية",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = ReemKufiFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (isAdmin) {
                        "${subjectList.size} مادة مفعلة ومضافة للطلاب من أصل ${state.totalSubjectsCount}"
                    } else {
                        "${subjectList.size} مادة مسجلة ومفتوحة في حسابك بجامعة حمص"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (subjectList.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isAdmin) Icons.Default.PostAdd else Icons.Default.BookmarkBorder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = if (isAdmin) "لم تقم بإضافة أي مادة بعد" else "لم تسجل أي مادة بعد",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = if (isAdmin) {
                                "عدد المواد المضافة حالياً 0. يمكنك الانتقال إلى قسم 'كل المواد' وتفعيل زر الإضافة الأخضر لأي مادة لتظهر هنا وللطلاب."
                            } else {
                                "تصفح مقررات الخطة الدراسية لجامعة حمص وسجل في مواد فصلك الحالي."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        Button(
                            onClick = onNavigateToBrowse,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DeepNavy,
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = if (isAdmin) "الذهاب إلى كل المواد وتفعيل المواد" else "تصفح المواد لإضافتها",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        } else {
            items(subjectList, key = { it.id }) { subject ->
                SubjectCard(
                    subject = subject,
                    isEnrolled = !isAdmin,
                    role = state.role,
                    isPublished = if (isAdmin) true else state.addedSubjectIds.contains(subject.id),
                    onActionClick = { onSelectSubject(subject.id) },
                    onTogglePublish = { onTogglePublishSubject?.invoke(subject.id) }
                )
            }
        }
    }
}

// ==============================================================================
// 3. SubjectDetailScreen: Detail View with the 3 Sections
// (شرح المادة ، اسئلة الدورات ، الاسئلة التي لم ترد)
// ==============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectDetailScreen(
    subject: Subject,
    state: AppUiState,
    viewModel: AppViewModel? = null,
    onBack: () -> Unit,
    onToggleTopicCompletion: (String) -> Unit,
    onAddCustomTopic: ((subjectId: String, title: String, description: String, attachments: List<TopicAttachment>) -> Unit)? = null,
    onRemoveCustomTopic: ((subjectId: String, topicId: String) -> Unit)? = null,
    onUpdateSupabaseConfig: ((SupabaseConfig) -> Unit)? = null
) {
    BackHandler(onBack = onBack)

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("شرح المادة", "اسئلة الدورات", "الاسئلة التي لم ترد")

    val liveState = viewModel?.uiState?.collectAsState()?.value ?: state
    val topics = remember(subject.id) { CurriculumData.getTopicsForSubject(subject.id) }
    val examPapers = remember(subject.id) { CurriculumData.getExamPapersForSubject(subject.id) }
    val unaskedQuestions = remember(subject.id) { CurriculumData.getUnaskedQuestionsForSubject(subject.id) }
    val customTopics = remember(liveState.customTopicsBySubject, subject.id) {
        liveState.customTopicsBySubject[subject.id].orEmpty()
    }

    // Educational Hierarchy progress
    val subjectUnits = liveState.unitsBySubject[subject.id].orEmpty()
    val allSubjectLessons = remember(subjectUnits, liveState.chaptersByUnit, liveState.lessonsByChapter, liveState.directLessonsByUnit) {
        val list = mutableListOf<CourseLesson>()
        subjectUnits.forEach { unit ->
            list.addAll(liveState.directLessonsByUnit[unit.id].orEmpty())
            liveState.chaptersByUnit[unit.id].orEmpty().forEach { ch ->
                list.addAll(liveState.lessonsByChapter[ch.id].orEmpty())
            }
        }
        list
    }

    val completedCount = remember(topics, customTopics, allSubjectLessons, liveState.completedTopicIds) {
        if (allSubjectLessons.isNotEmpty()) {
            allSubjectLessons.count { liveState.completedTopicIds.contains(it.id) }
        } else {
            val standardCount = topics.count { liveState.completedTopicIds.contains(it.id) }
            val customCount = customTopics.count { liveState.completedTopicIds.contains(it.id) }
            if (customTopics.isNotEmpty()) customCount else standardCount
        }
    }
    val totalCount = if (allSubjectLessons.isNotEmpty()) allSubjectLessons.size else if (customTopics.isNotEmpty()) customTopics.size else topics.size
    val progress = if (totalCount > 0) completedCount.toFloat() / totalCount.toFloat() else 0f
    val isAdmin = liveState.role == UserRole.ADMIN
    val isPublished = subject.isPublished || liveState.addedSubjectIds.contains(subject.id)

    if (!isAdmin && !isPublished) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("المادة غير منشورة") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "الرجوع")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.padding(24.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "عذراً، هذه المادة غير منشورة للطلاب حالياً",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "سيتم إتاحة محتوى المقرر فور نشره من قِبل المشرف.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = onBack) {
                            Text("العودة إلى المواد")
                        }
                    }
                }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = subject.name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${CurriculumData.getYearName(subject.year)} · ${CurriculumData.getSemesterName(subject.semester)} · جامعة حمص",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "الرجوع",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isAdmin) Color(0xFF10B981).copy(alpha = 0.15f) else EmeraldMint.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isAdmin) Color(0xFF10B981).copy(alpha = 0.35f) else EmeraldMint.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(
                            text = if (isAdmin) "لوحة المشرف" else "مادة مشتراة",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                            color = if (isAdmin) Color(0xFF059669) else EmeraldMint,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Subject Progress Summary Header
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isAdmin) "حالة محتوى المقرر الأكاديمي" else "تقدم إنجاز المادة",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isAdmin) {
                                if (subjectUnits.isNotEmpty() || allSubjectLessons.isNotEmpty()) {
                                    "${subjectUnits.size} وحدات · ${allSubjectLessons.size} دروس"
                                } else {
                                    "${customTopics.size} عناوين ومحاضرات مرفوعة"
                                }
                            } else {
                                "$completedCount من $totalCount دروس منجزة (${(progress * 100).toInt()}%)"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = EmeraldMint
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { if (isAdmin) (if (subjectUnits.isNotEmpty() || customTopics.isNotEmpty()) 1f else 0f) else progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = EmeraldMint,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            // The 3 Section Tabs (شرح المادة ، اسئلة الدورات ، الاسئلة التي لم ترد)
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                }
            ) {
                tabs.forEachIndexed { index, tabTitle ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = tabTitle,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.5.sp,
                                color = if (selectedTab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            }

            // Tab Content
            when (selectedTab) {
                0 -> CourseExplanationSection(
                    subjectId = subject.id,
                    subjectName = subject.name,
                    role = liveState.role,
                    viewModel = viewModel,
                    curriculumTopics = topics,
                    customTopics = customTopics,
                    completedTopicIds = liveState.completedTopicIds,
                    supabaseConfig = liveState.supabaseConfig,
                    onToggleTopicCompletion = onToggleTopicCompletion,
                    onAddCustomTopic = onAddCustomTopic,
                    onRemoveCustomTopic = onRemoveCustomTopic,
                    onUpdateSupabaseConfig = onUpdateSupabaseConfig
                )
                1 -> PastExamsSection(examPapers = examPapers)
                2 -> UnaskedQuestionsSection(questions = unaskedQuestions)
            }
        }
    }
}

// ------------------------------------------------------------------------------
// Section 1: شرح المادة (مع دعم إضافة عنوان ورفع ملفات Supabase للمشرف)
// ------------------------------------------------------------------------------
// ------------------------------------------------------------------------------
// Section 1: شرح المادة - الهيكلية التعليمية (الوحدات، الفصول، الدروس)
// ------------------------------------------------------------------------------
@Composable
fun CourseExplanationSection(
    subjectId: String,
    subjectName: String,
    role: UserRole,
    viewModel: AppViewModel? = null,
    curriculumTopics: List<TopicItem> = emptyList(),
    customTopics: List<AdminTopic> = emptyList(),
    completedTopicIds: Set<String> = emptySet(),
    supabaseConfig: SupabaseConfig = SupabaseConfig(),
    onToggleTopicCompletion: (String) -> Unit = {},
    onAddCustomTopic: ((subjectId: String, title: String, description: String, attachments: List<TopicAttachment>) -> Unit)? = null,
    onRemoveCustomTopic: ((subjectId: String, topicId: String) -> Unit)? = null,
    onUpdateSupabaseConfig: ((SupabaseConfig) -> Unit)? = null
) {
    val context = LocalContext.current
    val isAdmin = role == UserRole.ADMIN

    // Automatically load units for this subject when screen is launched
    LaunchedEffect(subjectId) {
        viewModel?.loadUnitsForSubject(subjectId)
    }

    val liveState = viewModel?.uiState?.collectAsState()?.value
    val units: List<CourseUnit> = liveState?.unitsBySubject?.get(subjectId).orEmpty()
    val chaptersByUnit: Map<String, List<CourseChapter>> = liveState?.chaptersByUnit ?: emptyMap()
    val lessonsByChapter: Map<String, List<CourseLesson>> = liveState?.lessonsByChapter ?: emptyMap()
    val directLessonsByUnit: Map<String, List<CourseLesson>> = liveState?.directLessonsByUnit ?: emptyMap()
    val loadingIds: Set<String> = liveState?.loadingHierarchyIds ?: emptySet()
    val isOperating: Boolean = liveState?.isHierarchyOperating == true
    val operationMessage: String? = liveState?.hierarchyOperationMessage
    val operationError: String? = liveState?.hierarchyOperationError

    // Expanded states
    var expandedUnitIds by remember { mutableStateOf(setOf<String>()) }
    var expandedChapterIds by remember { mutableStateOf(setOf<String>()) }

    // Dialog states
    var showAddUnitDialog by remember { mutableStateOf(false) }
    var unitToEdit by remember { mutableStateOf<CourseUnit?>(null) }

    var unitForNewChapter by remember { mutableStateOf<CourseUnit?>(null) }
    var chapterToEdit by remember { mutableStateOf<CourseChapter?>(null) }

    var newLessonContext by remember { mutableStateOf<NewLessonContextHolder?>(null) }
    var lessonToEdit by remember { mutableStateOf<CourseLesson?>(null) }

    var itemToDelete by remember { mutableStateOf<DeleteHierarchyItem?>(null) }

    // Visible units based on role
    val visibleUnits: List<CourseUnit> = remember(units, isAdmin) {
        if (isAdmin) units.sortedBy { it.orderIndex }
        else units.filter { it.isPublished }.sortedBy { it.orderIndex }
    }

    // Toggle unit expansion and load child items
    fun toggleUnit(unitId: String) {
        if (expandedUnitIds.contains(unitId)) {
            expandedUnitIds = expandedUnitIds - unitId
        } else {
            expandedUnitIds = expandedUnitIds + unitId
            viewModel?.loadChaptersForUnit(unitId)
            viewModel?.loadDirectLessonsForUnit(unitId)
        }
    }

    // Toggle chapter expansion and load child lessons
    fun toggleChapter(chapterId: String) {
        if (expandedChapterIds.contains(chapterId)) {
            expandedChapterIds = expandedChapterIds - chapterId
        } else {
            expandedChapterIds = expandedChapterIds + chapterId
            viewModel?.loadLessonsForChapter(chapterId)
        }
    }

    // Dialog handlers
    if (showAddUnitDialog) {
        AddEditUnitHierarchyDialog(
            unit = null,
            suggestedOrder = visibleUnits.size,
            onDismiss = { showAddUnitDialog = false },
            onSave = { title, desc, order, published ->
                viewModel?.createCourseUnit(
                    subjectId = subjectId,
                    title = title,
                    description = desc,
                    orderIndex = order,
                    isPublished = published
                )
                showAddUnitDialog = false
            }
        )
    }

    unitToEdit?.let { unit ->
        AddEditUnitHierarchyDialog(
            unit = unit,
            suggestedOrder = unit.orderIndex,
            onDismiss = { unitToEdit = null },
            onSave = { title, desc, order, published ->
                viewModel?.updateCourseUnit(
                    unitId = unit.id,
                    subjectId = subjectId,
                    title = title,
                    description = desc,
                    clearDescription = desc.isNullOrBlank(),
                    orderIndex = order,
                    isPublished = published
                )
                unitToEdit = null
            }
        )
    }

    unitForNewChapter?.let { parentUnit ->
        val currentChapters = chaptersByUnit[parentUnit.id].orEmpty()
        AddEditChapterHierarchyDialog(
            chapter = null,
            unitTitle = parentUnit.title,
            suggestedOrder = currentChapters.size,
            onDismiss = { unitForNewChapter = null },
            onSave = { title, desc, order, published ->
                viewModel?.createCourseChapter(
                    unitId = parentUnit.id,
                    subjectId = subjectId,
                    title = title,
                    description = desc,
                    orderIndex = order,
                    isPublished = published
                )
                unitForNewChapter = null
            }
        )
    }

    chapterToEdit?.let { chapter ->
        val parentUnit = units.firstOrNull { it.id == chapter.unitId }
        AddEditChapterHierarchyDialog(
            chapter = chapter,
            unitTitle = parentUnit?.title ?: "الوحدة",
            suggestedOrder = chapter.orderIndex,
            onDismiss = { chapterToEdit = null },
            onSave = { title, desc, order, published ->
                viewModel?.updateCourseChapter(
                    chapterId = chapter.id,
                    unitId = chapter.unitId,
                    title = title,
                    description = desc,
                    clearDescription = desc.isNullOrBlank(),
                    orderIndex = order,
                    isPublished = published
                )
                chapterToEdit = null
            }
        )
    }

    newLessonContext?.let { contextHolder ->
        val availableChapters = chaptersByUnit[contextHolder.unit.id].orEmpty()
        val defaultChapterId = contextHolder.defaultChapterId
        val existingLessonsCount = if (defaultChapterId != null) {
            lessonsByChapter[defaultChapterId].orEmpty().size
        } else {
            directLessonsByUnit[contextHolder.unit.id].orEmpty().size
        }

        AddEditLessonHierarchyDialog(
            lesson = null,
            unitTitle = contextHolder.unit.title,
            availableChapters = availableChapters,
            selectedChapterId = defaultChapterId,
            suggestedOrder = existingLessonsCount,
            onDismiss = { newLessonContext = null },
            onSave = { chId, title, summary, duration, order, published ->
                viewModel?.createCourseLesson(
                    unitId = contextHolder.unit.id,
                    subjectId = subjectId,
                    chapterId = chId,
                    title = title,
                    summary = summary,
                    durationMinutes = duration,
                    orderIndex = order,
                    isPublished = published
                )
                newLessonContext = null
            }
        )
    }

    lessonToEdit?.let { lesson ->
        val parentUnit = units.firstOrNull { it.id == lesson.unitId }
        val availableChapters = chaptersByUnit[lesson.unitId].orEmpty()
        AddEditLessonHierarchyDialog(
            lesson = lesson,
            unitTitle = parentUnit?.title ?: "الوحدة",
            availableChapters = availableChapters,
            selectedChapterId = lesson.chapterId,
            suggestedOrder = lesson.orderIndex,
            onDismiss = { lessonToEdit = null },
            onSave = { chId, title, summary, duration, order, published ->
                viewModel?.updateCourseLesson(
                    lessonId = lesson.id,
                    unitId = lesson.unitId,
                    chapterId = chId,
                    clearChapterId = (chId == null),
                    title = title,
                    summary = summary,
                    clearSummary = summary.isNullOrBlank(),
                    durationMinutes = duration,
                    clearDuration = duration == null,
                    orderIndex = order,
                    isPublished = published
                )
                lessonToEdit = null
            }
        )
    }

    itemToDelete?.let { target ->
        val (typeName, title, warning) = when (target) {
            is DeleteHierarchyItem.UnitItem -> Triple(
                "الوحدة",
                target.unit.title,
                "سيتم حذف هذه الوحدة نهائياً من قاعدة البيانات. تنبيه: قد يؤدي حذف الوحدة إلى حذف كافة الفصول والدروس التابعة لها."
            )
            is DeleteHierarchyItem.ChapterItem -> Triple(
                "الفصل",
                target.chapter.title,
                "سيتم حذف هذا الفصل نهائياً من قاعدة البيانات. تنبيه: قد يؤدي حذف الفصل إلى حذف كافة الدروس التابعة له."
            )
            is DeleteHierarchyItem.LessonItem -> Triple(
                "الدرس",
                target.lesson.title,
                "سيتم حذف هذا الدرس نهائياً من قاعدة البيانات."
            )
        }

        ConfirmDeleteHierarchyDialog(
            itemTypeName = typeName,
            itemTitle = title,
            impactWarning = warning,
            onDismiss = { itemToDelete = null },
            onConfirm = {
                when (target) {
                    is DeleteHierarchyItem.UnitItem -> viewModel?.deleteCourseUnit(target.unit.id, subjectId)
                    is DeleteHierarchyItem.ChapterItem -> viewModel?.deleteCourseChapter(target.chapter.id, target.chapter.unitId)
                    is DeleteHierarchyItem.LessonItem -> viewModel?.deleteCourseLesson(target.lesson.id, target.lesson.unitId, target.lesson.chapterId)
                }
                itemToDelete = null
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("section_course_explanation"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Operation / Status Feedback Banners
        val currentError = operationError
        if (currentError != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = currentError,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        IconButton(onClick = { viewModel?.clearHierarchyOperationMessages() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "إغلاق",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        val currentMessage = operationMessage
        if (currentMessage != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = EmeraldMint.copy(alpha = 0.15f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldMint.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = EmeraldMint
                            )
                            Text(
                                text = currentMessage,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = { viewModel?.clearHierarchyOperationMessages() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "إغلاق",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (isOperating) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = EmeraldMint
                )
            }
        }

        // Admin Header with Action Bar
        if (isAdmin) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountTree,
                                contentDescription = null,
                                tint = EmeraldMint,
                                modifier = Modifier.size(22.dp)
                            )
                            Column {
                                Text(
                                    text = "إدارة الهيكلية التعليمية",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${visibleUnits.size} وحدات مسجلة",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(
                                onClick = { viewModel?.loadUnitsForSubject(subjectId, forceReload = true) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "تحديث الهيكلية",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Button(
                                onClick = { showAddUnitDialog = true },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DeepNavy,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("btn_add_unit")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("إضافة وحدة", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Units Content
        if (loadingIds.contains("units_$subjectId") && visibleUnits.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(color = EmeraldMint, modifier = Modifier.size(36.dp))
                        Text(
                            text = "جاري تحميل الوحدات الدراسية...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (visibleUnits.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(EmeraldMint.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                tint = EmeraldMint,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = if (isAdmin) "لم تتم إضافة أي وحدة تعليمية بعد" else "لم يتم نشر محتوى تعليمي بعد",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = if (isAdmin)
                                "ابدأ ببناء الهيكلية التعليمية للمقرر بإضافة الوحدة الأولى. يمكنك لاحقاً إضافة فصول ودروس داخل كل وحدة."
                            else
                                "يقوم المشرف حالياً بتنظيم وحدات ودروس هذا المقرر، وستظهر هنا فور نشرها للطلاب.",
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        if (isAdmin) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { showAddUnitDialog = true },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EmeraldMint,
                                    contentColor = DeepNavy
                                )
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("إضافة أول وحدة الآن", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            // Render each Unit Card
            items(visibleUnits, key = { it.id }) { unit ->
                val isExpanded = expandedUnitIds.contains(unit.id)
                val isUnitLoading = loadingIds.contains("chapters_${unit.id}") || loadingIds.contains("direct_lessons_${unit.id}")

                val unitChapters = remember(chaptersByUnit, unit.id, isAdmin) {
                    val allCh = chaptersByUnit[unit.id].orEmpty()
                    if (isAdmin) allCh.sortedBy { it.orderIndex }
                    else allCh.filter { it.isPublished }.sortedBy { it.orderIndex }
                }

                val unitDirectLessons = remember(directLessonsByUnit, unit.id, isAdmin) {
                    val allL = directLessonsByUnit[unit.id].orEmpty()
                    if (isAdmin) allL.sortedBy { it.orderIndex }
                    else allL.filter { it.isPublished }.sortedBy { it.orderIndex }
                }

                UnitItemCard(
                    unit = unit,
                    isAdmin = isAdmin,
                    isExpanded = isExpanded,
                    isLoadingChildren = isUnitLoading,
                    chapters = unitChapters,
                    directLessons = unitDirectLessons,
                    lessonsByChapter = lessonsByChapter,
                    loadingIds = loadingIds,
                    completedTopicIds = completedTopicIds,
                    onToggleExpand = { toggleUnit(unit.id) },
                    onToggleChapterExpand = { toggleChapter(it) },
                    expandedChapterIds = expandedChapterIds,
                    onToggleLessonCompletion = onToggleTopicCompletion,
                    onAddChapter = { unitForNewChapter = unit },
                    onAddDirectLesson = { newLessonContext = NewLessonContextHolder(unit = unit, defaultChapterId = null) },
                    onAddLessonToChapter = { ch -> newLessonContext = NewLessonContextHolder(unit = unit, defaultChapterId = ch.id) },
                    onEditUnit = { unitToEdit = unit },
                    onDeleteUnit = { itemToDelete = DeleteHierarchyItem.UnitItem(unit) },
                    onTogglePublishUnit = {
                        viewModel?.updateCourseUnit(
                            unitId = unit.id,
                            subjectId = subjectId,
                            title = unit.title,
                            description = unit.description,
                            clearDescription = false,
                            orderIndex = unit.orderIndex,
                            isPublished = !unit.isPublished
                        )
                    },
                    onEditChapter = { ch -> chapterToEdit = ch },
                    onDeleteChapter = { ch -> itemToDelete = DeleteHierarchyItem.ChapterItem(ch) },
                    onTogglePublishChapter = { ch ->
                        viewModel?.updateCourseChapter(
                            chapterId = ch.id,
                            unitId = ch.unitId,
                            title = ch.title,
                            description = ch.description,
                            clearDescription = false,
                            orderIndex = ch.orderIndex,
                            isPublished = !ch.isPublished
                        )
                    },
                    onEditLesson = { ls -> lessonToEdit = ls },
                    onDeleteLesson = { ls -> itemToDelete = DeleteHierarchyItem.LessonItem(ls) },
                    onTogglePublishLesson = { ls ->
                        viewModel?.updateCourseLesson(
                            lessonId = ls.id,
                            unitId = ls.unitId,
                            chapterId = ls.chapterId,
                            title = ls.title,
                            summary = ls.summary,
                            clearSummary = false,
                            durationMinutes = ls.durationMinutes,
                            clearDuration = false,
                            orderIndex = ls.orderIndex,
                            isPublished = !ls.isPublished
                        )
                    }
                )
            }
        }
    }
}

// Helper context for new lesson
data class NewLessonContextHolder(
    val unit: CourseUnit,
    val defaultChapterId: String? = null
)

// Target for deletion dialog
sealed interface DeleteHierarchyItem {
    data class UnitItem(val unit: CourseUnit) : DeleteHierarchyItem
    data class ChapterItem(val chapter: CourseChapter) : DeleteHierarchyItem
    data class LessonItem(val lesson: CourseLesson) : DeleteHierarchyItem
}

// ------------------------------------------------------------------------------
// Unit Item Card (Expandable Unit Container)
// ------------------------------------------------------------------------------
@Composable
private fun UnitItemCard(
    unit: CourseUnit,
    isAdmin: Boolean,
    isExpanded: Boolean,
    isLoadingChildren: Boolean,
    chapters: List<CourseChapter>,
    directLessons: List<CourseLesson>,
    lessonsByChapter: Map<String, List<CourseLesson>>,
    loadingIds: Set<String>,
    completedTopicIds: Set<String>,
    expandedChapterIds: Set<String>,
    onToggleExpand: () -> Unit,
    onToggleChapterExpand: (String) -> Unit,
    onToggleLessonCompletion: (String) -> Unit,
    onAddChapter: () -> Unit,
    onAddDirectLesson: () -> Unit,
    onAddLessonToChapter: (CourseChapter) -> Unit,
    onEditUnit: () -> Unit,
    onDeleteUnit: () -> Unit,
    onTogglePublishUnit: () -> Unit,
    onEditChapter: (CourseChapter) -> Unit,
    onDeleteChapter: (CourseChapter) -> Unit,
    onTogglePublishChapter: (CourseChapter) -> Unit,
    onEditLesson: (CourseLesson) -> Unit,
    onDeleteLesson: (CourseLesson) -> Unit,
    onTogglePublishLesson: (CourseLesson) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("unit_card_${unit.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.2.dp,
            if (isExpanded) EmeraldMint.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isExpanded) 2.dp else 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Unit Header Row (Clickable to expand/collapse)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isExpanded) EmeraldMint else MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                            contentDescription = null,
                            tint = if (isExpanded) DeepNavy else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "الوحدة ${unit.orderIndex + 1}: ${unit.title}",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (isAdmin) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (unit.isPublished) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFF59E0B).copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (unit.isPublished) "منشور" else "مسودة",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp
                                        ),
                                        color = if (unit.isPublished) Color(0xFF059669) else Color(0xFFD97706),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        if (!unit.description.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = unit.description,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Header Actions
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (isAdmin) {
                        IconButton(
                            onClick = onTogglePublishUnit,
                            modifier = Modifier.size(32.dp).testTag("btn_toggle_publish_unit_${unit.id}")
                        ) {
                            Icon(
                                imageVector = if (unit.isPublished) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (unit.isPublished) "إلغاء النشر" else "نشر الوحدة",
                                tint = if (unit.isPublished) EmeraldMint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = onEditUnit,
                            modifier = Modifier.size(32.dp).testTag("btn_edit_unit_${unit.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "تعديل الوحدة",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = onDeleteUnit,
                            modifier = Modifier.size(32.dp).testTag("btn_delete_unit_${unit.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "حذف الوحدة",
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier.size(32.dp).testTag("btn_toggle_expand_unit_${unit.id}")
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "طي" else "توسيع",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Expanded Unit Body
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Admin Actions Toolbar within Unit
                    if (isAdmin) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = onAddChapter,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("btn_add_chapter_${unit.id}")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("إضافة فصل", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = onAddDirectLesson,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DeepNavy,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("btn_add_lesson_${unit.id}")
                            ) {
                                Icon(Icons.Default.PostAdd, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("إضافة درس مباشر", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Children Loading Indicator
                    if (isLoadingChildren) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = EmeraldMint)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "جاري تحميل محتوى الوحدة...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (chapters.isEmpty() && directLessons.isEmpty()) {
                        // Empty children state
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = if (isAdmin)
                                    "لا توجد فصول أو دروس مضافة لهذه الوحدة بعد. استخدم الأزرار أعلاه لإضافة أول فصل أو درس."
                                else
                                    "لا توجد دروس منشورة في هذه الوحدة حالياً.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            )
                        }
                    } else {
                        // Chapters Section
                        if (chapters.isNotEmpty()) {
                            Text(
                                text = "فصول الوحدة (${chapters.size}):",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )

                            chapters.forEach { chapter ->
                                val isChapterExpanded = expandedChapterIds.contains(chapter.id)
                                val chapterLessons = remember(lessonsByChapter, chapter.id, isAdmin) {
                                    val allL = lessonsByChapter[chapter.id].orEmpty()
                                    if (isAdmin) allL.sortedBy { it.orderIndex }
                                    else allL.filter { it.isPublished }.sortedBy { it.orderIndex }
                                }
                                val isChapterLoading = loadingIds.contains("chapter_lessons_${chapter.id}")

                                ChapterItemCard(
                                    chapter = chapter,
                                    isAdmin = isAdmin,
                                    isExpanded = isChapterExpanded,
                                    isLoadingLessons = isChapterLoading,
                                    lessons = chapterLessons,
                                    completedTopicIds = completedTopicIds,
                                    onToggleExpand = { onToggleChapterExpand(chapter.id) },
                                    onToggleLessonCompletion = onToggleLessonCompletion,
                                    onAddLesson = { onAddLessonToChapter(chapter) },
                                    onEditChapter = { onEditChapter(chapter) },
                                    onDeleteChapter = { onDeleteChapter(chapter) },
                                    onTogglePublishChapter = { onTogglePublishChapter(chapter) },
                                    onEditLesson = onEditLesson,
                                    onDeleteLesson = onDeleteLesson,
                                    onTogglePublishLesson = onTogglePublishLesson
                                )
                            }
                        }

                        // Direct Lessons (Belong to unit with no chapter)
                        if (directLessons.isNotEmpty()) {
                            if (chapters.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "دروس مباشرة تحت الوحدة (${directLessons.size}):",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            directLessons.forEach { lesson ->
                                LessonItemCard(
                                    lesson = lesson,
                                    isAdmin = isAdmin,
                                    isCompleted = completedTopicIds.contains(lesson.id),
                                    onToggleCompletion = { onToggleLessonCompletion(lesson.id) },
                                    onEdit = { onEditLesson(lesson) },
                                    onDelete = { onDeleteLesson(lesson) },
                                    onTogglePublish = { onTogglePublishLesson(lesson) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------
// Chapter Item Card (Expandable Chapter inside Unit)
// ------------------------------------------------------------------------------
@Composable
private fun ChapterItemCard(
    chapter: CourseChapter,
    isAdmin: Boolean,
    isExpanded: Boolean,
    isLoadingLessons: Boolean,
    lessons: List<CourseLesson>,
    completedTopicIds: Set<String>,
    onToggleExpand: () -> Unit,
    onToggleLessonCompletion: (String) -> Unit,
    onAddLesson: () -> Unit,
    onEditChapter: () -> Unit,
    onDeleteChapter: () -> Unit,
    onTogglePublishChapter: () -> Unit,
    onEditLesson: (CourseLesson) -> Unit,
    onDeleteLesson: (CourseLesson) -> Unit,
    onTogglePublishLesson: (CourseLesson) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chapter_card_${chapter.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.CollectionsBookmark else Icons.Default.BookmarkBorder,
                        contentDescription = null,
                        tint = DeepNavy,
                        modifier = Modifier.size(20.dp)
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "الفصل ${chapter.orderIndex + 1}: ${chapter.title}",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (isAdmin) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (chapter.isPublished) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFF59E0B).copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (chapter.isPublished) "منشور" else "مسودة",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                        color = if (chapter.isPublished) Color(0xFF059669) else Color(0xFFD97706),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }

                        if (!chapter.description.isNullOrBlank()) {
                            Text(
                                text = chapter.description,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isAdmin) {
                        IconButton(
                            onClick = onTogglePublishChapter,
                            modifier = Modifier.size(28.dp).testTag("btn_toggle_publish_chapter_${chapter.id}")
                        ) {
                            Icon(
                                imageVector = if (chapter.isPublished) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "تبديل النشر",
                                tint = if (chapter.isPublished) EmeraldMint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = onEditChapter,
                            modifier = Modifier.size(28.dp).testTag("btn_edit_chapter_${chapter.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "تعديل الفصل",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = onDeleteChapter,
                            modifier = Modifier.size(28.dp).testTag("btn_delete_chapter_${chapter.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "حذف الفصل",
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier.size(28.dp).testTag("btn_toggle_expand_chapter_${chapter.id}")
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "طي" else "توسيع",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isAdmin) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = onAddLesson,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("btn_add_lesson_in_chapter_${chapter.id}")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("إضافة درس في هذا الفصل", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (isLoadingLessons) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = EmeraldMint)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("جاري تحميل الدروس...", style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
                        }
                    } else if (lessons.isEmpty()) {
                        Text(
                            text = if (isAdmin) "لا توجد دروس في هذا الفصل بعد. اضغط 'إضافة درس' لإضافة أول درس."
                            else "لا توجد دروس منشورة في هذا الفصل حالياً.",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(8.dp)
                        )
                    } else {
                        lessons.forEach { lesson ->
                            LessonItemCard(
                                lesson = lesson,
                                isAdmin = isAdmin,
                                isCompleted = completedTopicIds.contains(lesson.id),
                                onToggleCompletion = { onToggleLessonCompletion(lesson.id) },
                                onEdit = { onEditLesson(lesson) },
                                onDelete = { onDeleteLesson(lesson) },
                                onTogglePublish = { onTogglePublishLesson(lesson) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------
// Lesson Item Card (With Completion Checkbox & PDF Placeholder)
// ------------------------------------------------------------------------------
@Composable
private fun LessonItemCard(
    lesson: CourseLesson,
    isAdmin: Boolean,
    isCompleted: Boolean,
    onToggleCompletion: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePublish: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("lesson_card_${lesson.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCompleted) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isCompleted) EmeraldMint.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
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
                    if (!isAdmin) {
                        Checkbox(
                            checked = isCompleted,
                            onCheckedChange = { onToggleCompletion() },
                            colors = CheckboxDefaults.colors(
                                checkedColor = EmeraldMint,
                                checkmarkColor = DeepNavy
                            ),
                            modifier = Modifier.size(24.dp).testTag("checkbox_lesson_${lesson.id}")
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(DeepNavy.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = DeepNavy,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = lesson.title,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (lesson.durationMinutes != null && lesson.durationMinutes > 0) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = "⏳ ${lesson.durationMinutes} دقيقة",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            if (isAdmin) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (lesson.isPublished) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFF59E0B).copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (lesson.isPublished) "منشور" else "مسودة",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                        color = if (lesson.isPublished) Color(0xFF059669) else Color(0xFFD97706),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }

                        if (!lesson.summary.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = lesson.summary,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (isAdmin) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onTogglePublish,
                            modifier = Modifier.size(28.dp).testTag("btn_toggle_publish_lesson_${lesson.id}")
                        ) {
                            Icon(
                                imageVector = if (lesson.isPublished) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "تبديل النشر",
                                tint = if (lesson.isPublished) EmeraldMint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier.size(28.dp).testTag("btn_edit_lesson_${lesson.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "تعديل الدرس",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(28.dp).testTag("btn_delete_lesson_${lesson.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "حذف الدرس",
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // PDF Files Placeholder (Phase 2-B3-B1 Specification)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureAsPdf,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "ملفات ومرفقات الدرس",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (isAdmin) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = DeepNavy.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = "المرحلة القادمة 2-B3-B2",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = DeepNavy,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "لا توجد ملفات مرفقة حالياً بهذا الدرس.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isAdmin) {
                        Text(
                            text = "سيتم تفعيل رفع وإدارة ملفات الـ PDF وربطها بهذا الدرس في المرحلة 2-B3-B2.",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------
// Dialog: Add / Edit Unit
// ------------------------------------------------------------------------------
@Composable
fun AddEditUnitHierarchyDialog(
    unit: CourseUnit?,
    suggestedOrder: Int,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String?, orderIndex: Int, isPublished: Boolean) -> Unit
) {
    var title by remember { mutableStateOf(unit?.title ?: "") }
    var description by remember { mutableStateOf(unit?.description ?: "") }
    var orderIndexText by remember { mutableStateOf((unit?.orderIndex ?: suggestedOrder).toString()) }
    var isPublished by remember { mutableStateOf(unit?.isPublished ?: true) }
    var titleError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (unit == null) "إضافة وحدة تعليمية جديدة" else "تعديل الوحدة",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        if (it.isNotBlank()) titleError = false
                    },
                    label = { Text("عنوان الوحدة *") },
                    isError = titleError,
                    supportingText = if (titleError) {
                        { Text("يرجى إدخال عنوان الوحدة") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("وصف الوحدة (اختياري)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = orderIndexText,
                    onValueChange = { orderIndexText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("ترتيب العرض (الرقم الترتيبي)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("نشر الوحدة للطلاب", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                        Text(
                            text = if (isPublished) "ستظهر الوحدة للطلاب فوراً" else "ستبقى كمسودة مرئية للمشرف فقط",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isPublished,
                        onCheckedChange = { isPublished = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = EmeraldMint,
                            checkedTrackColor = DeepNavy
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isBlank()) {
                        titleError = true
                    } else {
                        val order = orderIndexText.toIntOrNull() ?: 0
                        onSave(title.trim(), description.trim().ifEmpty { null }, order, isPublished)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DeepNavy)
            ) {
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

// ------------------------------------------------------------------------------
// Dialog: Add / Edit Chapter
// ------------------------------------------------------------------------------
@Composable
fun AddEditChapterHierarchyDialog(
    chapter: CourseChapter?,
    unitTitle: String,
    suggestedOrder: Int,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String?, orderIndex: Int, isPublished: Boolean) -> Unit
) {
    var title by remember { mutableStateOf(chapter?.title ?: "") }
    var description by remember { mutableStateOf(chapter?.description ?: "") }
    var orderIndexText by remember { mutableStateOf((chapter?.orderIndex ?: suggestedOrder).toString()) }
    var isPublished by remember { mutableStateOf(chapter?.isPublished ?: true) }
    var titleError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = if (chapter == null) "إضافة فصل جديد" else "تعديل الفصل",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "تابع للوحدة: $unitTitle",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmeraldMint
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        if (it.isNotBlank()) titleError = false
                    },
                    label = { Text("عنوان الفصل *") },
                    isError = titleError,
                    supportingText = if (titleError) {
                        { Text("يرجى إدخال عنوان الفصل") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("وصف الفصل (اختياري)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = orderIndexText,
                    onValueChange = { orderIndexText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("ترتيب العرض (الرقم الترتيبي)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("نشر الفصل للطلاب", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                        Text(
                            text = if (isPublished) "سيظهر الفصل للطلاب" else "مسودة للمشرف فقط",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isPublished,
                        onCheckedChange = { isPublished = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = EmeraldMint,
                            checkedTrackColor = DeepNavy
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isBlank()) {
                        titleError = true
                    } else {
                        val order = orderIndexText.toIntOrNull() ?: 0
                        onSave(title.trim(), description.trim().ifEmpty { null }, order, isPublished)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DeepNavy)
            ) {
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

// ------------------------------------------------------------------------------
// Dialog: Add / Edit Lesson
// ------------------------------------------------------------------------------
@Composable
fun AddEditLessonHierarchyDialog(
    lesson: CourseLesson?,
    unitTitle: String,
    availableChapters: List<CourseChapter>,
    selectedChapterId: String?,
    suggestedOrder: Int,
    onDismiss: () -> Unit,
    onSave: (chapterId: String?, title: String, summary: String?, durationMinutes: Int?, orderIndex: Int, isPublished: Boolean) -> Unit
) {
    var title by remember { mutableStateOf(lesson?.title ?: "") }
    var summary by remember { mutableStateOf(lesson?.summary ?: "") }
    var durationText by remember { mutableStateOf(lesson?.durationMinutes?.toString() ?: "") }
    var orderIndexText by remember { mutableStateOf((lesson?.orderIndex ?: suggestedOrder).toString()) }
    var isPublished by remember { mutableStateOf(lesson?.isPublished ?: true) }
    var currentChapterId by remember { mutableStateOf(lesson?.chapterId ?: selectedChapterId) }
    var titleError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = if (lesson == null) "إضافة درس جديد" else "تعديل الدرس",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "تابع للوحدة: $unitTitle",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmeraldMint
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Chapter Selection
                if (availableChapters.isNotEmpty()) {
                    Text(
                        text = "اختر الفصل التابع له الدرس (أو اتركه مباشر):",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = currentChapterId == null,
                                onClick = { currentChapterId = null },
                                label = { Text("بدون فصل (مباشر في الوحدة)", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = EmeraldMint,
                                    selectedLabelColor = DeepNavy
                                )
                            )
                        }

                        items(availableChapters, key = { it.id }) { ch ->
                            FilterChip(
                                selected = currentChapterId == ch.id,
                                onClick = { currentChapterId = ch.id },
                                label = { Text(ch.title, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = DeepNavy,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        if (it.isNotBlank()) titleError = false
                    },
                    label = { Text("عنوان الدرس *") },
                    isError = titleError,
                    supportingText = if (titleError) {
                        { Text("يرجى إدخال عنوان الدرس") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("ملخص أو نبذة عن الدرس (اختياري)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { durationText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("المدة (بالدقائق)") },
                        placeholder = { Text("مثال: 30") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    )

                    OutlinedTextField(
                        value = orderIndexText,
                        onValueChange = { orderIndexText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("ترتيب العرض") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("نشر الدرس للطلاب", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                        Text(
                            text = if (isPublished) "سيتمكن الطلاب من مشاهدته" else "مسودة للمشرف فقط",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isPublished,
                        onCheckedChange = { isPublished = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = EmeraldMint,
                            checkedTrackColor = DeepNavy
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isBlank()) {
                        titleError = true
                    } else {
                        val duration = durationText.toIntOrNull()
                        val order = orderIndexText.toIntOrNull() ?: 0
                        onSave(
                            currentChapterId,
                            title.trim(),
                            summary.trim().ifEmpty { null },
                            duration,
                            order,
                            isPublished
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DeepNavy)
            ) {
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

// ------------------------------------------------------------------------------
// Dialog: Delete Hierarchy Item Confirmation
// ------------------------------------------------------------------------------
@Composable
fun ConfirmDeleteHierarchyDialog(
    itemTypeName: String,
    itemTitle: String,
    impactWarning: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "تأكيد حذف $itemTypeName",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "هل أنت متأكد من حذف $itemTypeName: «$itemTitle»؟",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = impactWarning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("نعم، حذف نهائي")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

// ------------------------------------------------------------------------------
// Dialog for Admin to Add New Topic Title and Upload PDF to Supabase
// ------------------------------------------------------------------------------
@Composable
fun AddTopicWithFileDialog(
    subjectId: String,
    supabaseConfig: SupabaseConfig,
    onDismiss: () -> Unit,
    onConfirm: (title: String, description: String, attachments: List<TopicAttachment>) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }
    var selectedFileSize by remember { mutableStateOf("") }
    var isUploading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            selectedFileName = SupabaseStorageService.getFileName(context, uri)
            selectedFileSize = try {
                val stream = context.contentResolver.openInputStream(uri)
                val len = stream?.available()?.toLong() ?: 0L
                SupabaseStorageService.formatFileSize(len)
            } catch (_: Exception) {
                "PDF"
            }
            errorMessage = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isUploading) onDismiss() },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, tint = EmeraldMint)
                Text(
                    text = "إضافة عنوان ورفع ملف للمادة",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "أدخل عنوان الدرس أو الوحدة، وحدد ملف الـ PDF من هاتفك ليتم رفعه إلى Supabase:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("عنوان الدرس / المحاضرة *") },
                    placeholder = { Text("مثال: المحاضرة 1: مدخل ومفاهيم أساسية") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_topic_title"),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("وصف أو ملخص مختصر (اختياري)") },
                    placeholder = { Text("ملاحظات للطلاب حول محتوى الملف...") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 2
                )

                // File Attachment Section
                Text(
                    text = "الملف المرفق (PDF):",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (selectedUri == null) {
                    OutlinedButton(
                        onClick = { filePicker.launch("application/pdf") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_select_file"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إضافة ملف (تحديد PDF من الهاتف)", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldMint)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.PictureAsPdf,
                                    contentDescription = null,
                                    tint = Color(0xFFEF4444)
                                )
                                Column {
                                    Text(
                                        text = selectedFileName,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = selectedFileSize,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            IconButton(
                                onClick = { filePicker.launch("application/pdf") },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "تغيير الملف", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                if (isUploading) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = EmeraldMint
                        )
                        Text(
                            text = "جاري رفع الملف إلى Supabase Storage...",
                            style = MaterialTheme.typography.bodySmall,
                            color = EmeraldMint
                        )
                    }
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFEF4444)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isBlank()) {
                        errorMessage = "يرجى كتابة عنوان للدرس أو المحاضرة"
                        return@Button
                    }
                    if (selectedUri == null) {
                        errorMessage = "يرجى الضغط على زر 'إضافة ملف' وتحديد ملف PDF من هاتفك"
                        return@Button
                    }

                    isUploading = true
                    errorMessage = null
                    coroutineScope.launch {
                        val result = SupabaseStorageService.uploadFile(
                            context = context,
                            fileUri = selectedUri!!,
                            fileName = selectedFileName,
                            subjectId = subjectId,
                            config = supabaseConfig
                        )
                        isUploading = false
                        result.onSuccess { attachment ->
                            onConfirm(title, description, listOf(attachment))
                        }.onFailure { err ->
                            errorMessage = err.message ?: "حدث خطأ أثناء الرفع"
                        }
                    }
                },
                enabled = !isUploading && title.isNotBlank() && selectedUri != null,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DeepNavy, contentColor = Color.White),
                modifier = Modifier.testTag("btn_confirm_upload_topic")
            ) {
                Text("رفع إلى Supabase وحفظ العنوان", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isUploading
            ) {
                Text("إلغاء")
            }
        }
    )
}

// ------------------------------------------------------------------------------
// Dialog for Supabase Storage Configuration
// ------------------------------------------------------------------------------
@Composable
fun SupabaseConfigDialog(
    currentConfig: SupabaseConfig,
    onDismiss: () -> Unit,
    onSave: (SupabaseConfig) -> Unit
) {
    var url by remember { mutableStateOf(currentConfig.projectUrl) }
    var anonKey by remember { mutableStateOf(currentConfig.anonKey) }
    var bucket by remember { mutableStateOf(currentConfig.bucketName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Cloud, contentDescription = null, tint = EmeraldMint)
                Text("إعدادات Supabase Storage", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "يمكنك ربط مشروع Supabase الخاص بك لرفع الملفات والوصول إليها عبر السحابة:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Project URL") },
                    placeholder = { Text("https://xyzcompany.supabase.co") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = anonKey,
                    onValueChange = { anonKey = it },
                    label = { Text("Anon Key (Public)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(
                    value = bucket,
                    onValueChange = { bucket = it },
                    label = { Text("Storage Bucket Name") },
                    placeholder = { Text("subject-files") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(SupabaseConfig(projectUrl = url.trim(), anonKey = anonKey.trim(), bucketName = bucket.trim().ifBlank { "subject-files" }))
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DeepNavy, contentColor = Color.White)
            ) {
                Text("حفظ الإعدادات", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

fun openAttachment(context: android.content.Context, attachment: TopicAttachment) {
    try {
        val uri = if (attachment.localUri != null) {
            Uri.parse(attachment.localUri)
        } else {
            Uri.parse(attachment.supabaseUrl)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(attachment.supabaseUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "تعذر فتح الملف: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

// ------------------------------------------------------------------------------
// Section 2: اسئلة الدورات
// ------------------------------------------------------------------------------
@Composable
fun PastExamsSection(
    examPapers: List<ExamPaper>
) {
    if (examPapers.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(EmeraldMint.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Quiz,
                            contentDescription = null,
                            tint = EmeraldMint,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "قسم أسئلة الدورات فارغ حالياً",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "لا توجد أسئلة دورات مضافة لهذه المادة حالياً. سيتم إضافتها لاحقاً.",
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        return
    }

    var selectedPaperIndex by remember { mutableIntStateOf(0) }
    val currentPaper = examPapers.getOrNull(selectedPaperIndex) ?: examPapers.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Exam Session Selector Row
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "اختر دورة امتحانية لدراسة وحل أسئلتها:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(examPapers.indices.toList()) { index ->
                        val paper = examPapers[index]
                        val isSelected = selectedPaperIndex == index
                        Surface(
                            onClick = { selectedPaperIndex = index },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) DeepNavy else MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) DeepNavy else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Text(
                                text = paper.title,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        if (currentPaper != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = currentPaper.title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "المدة: ${currentPaper.durationMinutes} دقيقة · إجمالي ${currentPaper.questionsCount} سؤال مؤتمت",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = EmeraldMint.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldMint.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "دورة رسمية",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = EmeraldMint,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            items(currentPaper.questions, key = { it.id }) { question ->
                ExamQuestionCard(question = question)
            }
        }
    }
}

@Composable
fun ExamQuestionCard(question: ExamQuestion) {
    var selectedOption by remember { mutableStateOf<Int?>(null) }
    var showExplanation by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DeepNavy,
                    contentColor = Color.White
                ) {
                    Text(
                        text = "السؤال ${question.questionNumber}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                if (selectedOption != null) {
                    val isCorrect = selectedOption == question.correctIndex
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCorrect) EmeraldMint.copy(alpha = 0.15f) else MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = if (isCorrect) "إجابة صحيحة ✓" else "إجابة خاطئة ✗",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (isCorrect) EmeraldMint else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = question.text,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Options (A, B, C, D)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                question.options.forEachIndexed { optIndex, optionText ->
                    val isSelected = selectedOption == optIndex
                    val isCorrect = optIndex == question.correctIndex
                    val showResult = selectedOption != null

                    val bgColor = when {
                        !showResult -> if (isSelected) DeepNavy else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        isSelected && isCorrect -> EmeraldMint.copy(alpha = 0.2f)
                        isSelected && !isCorrect -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                        isCorrect -> EmeraldMint.copy(alpha = 0.12f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    }

                    val borderColor = when {
                        !showResult -> if (isSelected) DeepNavy else MaterialTheme.colorScheme.outlineVariant
                        isSelected && isCorrect -> EmeraldMint
                        isSelected && !isCorrect -> MaterialTheme.colorScheme.error
                        isCorrect -> EmeraldMint.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    }

                    Surface(
                        onClick = {
                            selectedOption = optIndex
                            showExplanation = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = bgColor,
                        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${('أ'.code + optIndex).toChar()})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (selectedOption == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = optionText,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Explanation section
            if (showExplanation) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldMint.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = EmeraldMint, modifier = Modifier.size(16.dp))
                            Text(
                                text = "توضيح وشرح الحل النموذجي:",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = question.explanation,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------
// Section 3: الأسئلة التي لم ترد
// ------------------------------------------------------------------------------
@Composable
fun UnaskedQuestionsSection(
    questions: List<UnaskedQuestion>
) {
    if (questions.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF59E0B).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = null,
                            tint = Color(0xFFD97706),
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "قسم الأسئلة التي لم ترد فارغ حالياً",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "لا توجد أسئلة متوقعة أو غير واردة مضافة لهذه المادة حالياً. سيتم إضافتها لاحقاً.",
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = EmeraldMint.copy(alpha = 0.12f)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldMint.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = EmeraldMint,
                        modifier = Modifier.size(28.dp)
                    )
                    Column {
                        Text(
                            text = "بنك الأسئلة المتوقعة (لم ترد سابقاً)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "أسئلة مركزة أعدها أساتذة جامعة حمص تغطي ثغرات الدورات السابقة ومرشحة للاختبارات القادمة.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        items(questions, key = { it.id }) { q ->
            UnaskedQuestionCard(q = q)
        }
    }
}

@Composable
fun UnaskedQuestionCard(q: UnaskedQuestion) {
    var selectedOption by remember { mutableStateOf<Int?>(null) }
    var showExplanation by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFF59E0B).copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.35f))
                ) {
                    Text(
                        text = q.priority,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFD97706),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Text(
                    text = q.topicName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = q.title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = q.questionText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 22.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(14.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                q.options.forEachIndexed { optIndex, optionText ->
                    val isSelected = selectedOption == optIndex
                    val isCorrect = optIndex == q.correctIndex
                    val showResult = selectedOption != null

                    val bgColor = when {
                        !showResult -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        isSelected && isCorrect -> EmeraldMint.copy(alpha = 0.2f)
                        isSelected && !isCorrect -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                        isCorrect -> EmeraldMint.copy(alpha = 0.12f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    }

                    Surface(
                        onClick = {
                            selectedOption = optIndex
                            showExplanation = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = bgColor,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (showResult && isCorrect) EmeraldMint else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${('أ'.code + optIndex).toChar()})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = optionText,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            if (showExplanation) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldMint.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "سبب التوقع وتوضيح الحل:",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = EmeraldMint
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = q.explanation,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ==============================================================================
// 4. تصفح المواد / كل المواد (Browse Courses / All Courses Section)
// ==============================================================================
@Composable
fun BrowseCoursesScreen(
    state: AppUiState,
    onSearchChange: (String) -> Unit,
    onFilterYearChange: (Int?) -> Unit,
    onFilterSemesterChange: (Int?) -> Unit,
    onToggleEnroll: (String) -> Unit,
    onTogglePublishSubject: ((String) -> Unit)? = null,
    onSelectSubject: ((String) -> Unit)? = null,
    onRetry: (() -> Unit)? = null
) {
    val isAdmin = state.role == UserRole.ADMIN

    // للطالب: جلب وعرض المواد المنشورة فقط
    // للمشرف: عرض جميع المواد الـ 59 مع حالة النشر الحقيقية
    val baseSubjects = remember(isAdmin, state.subjects) {
        if (isAdmin) {
            state.subjects
        } else {
            state.subjects.filter { it.isPublished }
        }
    }

    val filteredList = remember(baseSubjects, state.searchQuery, state.filterYear, state.filterSemester) {
        baseSubjects.filter { subject ->
            val matchQuery = state.searchQuery.isBlank() || subject.name.contains(state.searchQuery.trim(), ignoreCase = true)
            val matchYear = state.filterYear == null || subject.academicYear == state.filterYear
            val matchSem = state.filterSemester == null || subject.semester == state.filterSemester
            matchQuery && matchYear && matchSem
        }
    }

    val availableYears = remember(state.academicYears) {
        if (state.academicYears.isNotEmpty()) {
            state.academicYears.map { it.id to it.name }
        } else {
            (1..5).map { it to CurriculumData.getYearName(it) }
        }
    }

    val availableSemesters = remember(state.semesters) {
        if (state.semesters.isNotEmpty()) {
            state.semesters.map { it.id to it.name }
        } else {
            listOf(1 to "الفصل الأول", 2 to "الفصل الثاني")
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("screen_browse"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    text = if (isAdmin) "لوحة تحكم المشرف · جامعة حمص" else "الخطة الدراسية الكاملة · جامعة حمص",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isAdmin) "كل المواد" else "تصفح المواد",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = ReemKufiFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (isAdmin) {
                        "تفعيل ظهور المواد لحسابات الطلاب من خلال زر التفعيل الأخضر، والضغط على المادة لإدارتها ورفع ملفاتها."
                    } else {
                        "جميع مقررات الخطة الدراسية مرتبة حسب السنة والفصل."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_subject_input"),
                placeholder = { Text("ابحث عن مادة…") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "بحث", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "مسح", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = EmeraldMint,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "تصفية حسب السنة الدراسية:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterPill(
                            selected = state.filterYear == null,
                            label = "كل السنوات",
                            onClick = { onFilterYearChange(null) }
                        )
                    }
                    items(availableYears, key = { it.first }) { (yrId, yrName) ->
                        FilterPill(
                            selected = state.filterYear == yrId,
                            label = yrName,
                            onClick = { onFilterYearChange(if (state.filterYear == yrId) null else yrId) }
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "تصفية حسب الفصل:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterPill(
                        selected = state.filterSemester == null,
                        label = "كل الفصول",
                        onClick = { onFilterSemesterChange(null) }
                    )
                    availableSemesters.forEach { (semId, semName) ->
                        FilterPill(
                            selected = state.filterSemester == semId,
                            label = semName,
                            onClick = { onFilterSemesterChange(if (state.filterSemester == semId) null else semId) }
                        )
                    }
                }
            }
        }

        if (state.subjectsError != null && state.subjects.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Text(state.subjectsError ?: "تعذر تحديث البيانات", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        IconButton(onClick = { onRetry?.invoke() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "إعادة المحاولة", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "${filteredList.size} مادة معروضة",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (state.isSyncingWithSupabase && !state.isSubjectsLoaded) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(42.dp),
                            color = EmeraldMint,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "جارٍ تحميل المواد الدراسية…",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "يتم الاتصال بقاعدة بيانات Supabase لجلب المقررات وحالة النشر.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else if (state.subjectsError != null && state.subjects.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "خطأ",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "تعذر تحميل المواد الدراسية",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = state.subjectsError ?: "فشل الاتصال بقاعدة البيانات",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Button(
                            onClick = { onRetry?.invoke() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier.testTag("retry_fetch_subjects_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("إعادة المحاولة", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else if (filteredList.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        val isFilterActive = state.searchQuery.isNotBlank() || state.filterYear != null || state.filterSemester != null
                        Text(
                            text = if (isFilterActive) {
                                "لا توجد مواد تطابق خيارات التصفية أو البحث"
                            } else if (isAdmin) {
                                "لا توجد مواد في قاعدة البيانات حالياً"
                            } else {
                                "لا توجد مواد دراسية منشورة حالياً"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isFilterActive) {
                                "جرب تعديل كلمة البحث أو إزالة مرشح السنة أو الفصل لعرض المزيد من المواد."
                            } else if (isAdmin) {
                                "تأكد من إدخال سجلات المواد في جدول public.subjects في Supabase."
                            } else {
                                "لم يقم المشرف بنشر أي مواد في هذه الخطة بعد. ستظهر المواد هنا فور نشرها من المشرف."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (isFilterActive) {
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(
                                onClick = {
                                    onSearchChange("")
                                    onFilterYearChange(null)
                                    onFilterSemesterChange(null)
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("إلغاء جميع الفلاتر")
                            }
                        }
                    }
                }
            }
        } else {
            items(filteredList, key = { it.id }) { subject ->
                SubjectCard(
                    subject = subject,
                    isEnrolled = if (isAdmin) false else state.enrolledSubjectIds.contains(subject.id),
                    role = state.role,
                    isPublished = subject.isPublished || state.addedSubjectIds.contains(subject.id),
                    onActionClick = {
                        if (isAdmin) {
                            onSelectSubject?.invoke(subject.id)
                        } else {
                            onToggleEnroll(subject.id)
                        }
                    },
                    onTogglePublish = {
                        onTogglePublishSubject?.invoke(subject.id)
                    }
                )
            }
        }
    }
}

// ==============================================================================
// 5. ملفي الشخصي (Profile Section)
// ==============================================================================
@Composable
fun ProfileScreen(
    state: AppUiState,
    onToggleDarkMode: () -> Unit,
    onYearChange: (Int) -> Unit,
    onSemesterChange: (Int) -> Unit,
    onLogout: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("screen_profile"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(
                                if (state.role == UserRole.ADMIN) Color(0xFFF59E0B).copy(alpha = 0.18f)
                                else EmeraldMint.copy(alpha = 0.18f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (state.role == UserRole.ADMIN) "أ" else "ط",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (state.role == UserRole.ADMIN) Color(0xFFD97706) else MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = if (state.role == UserRole.ADMIN) "المشرف العام (admin)" else "طالب جامعي (user)",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (state.role == UserRole.ADMIN) "مشرف النظام · هندسة الميكاترونيك" else "طالب هندسة ميكاترونيك · جامعة حمص",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (state.role == UserRole.ADMIN) Color(0xFFF59E0B).copy(alpha = 0.15f)
                        else EmeraldMint.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (state.role == UserRole.ADMIN) Color(0xFFF59E0B).copy(alpha = 0.4f)
                            else EmeraldMint.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (state.role == UserRole.ADMIN) Icons.Default.Shield else Icons.Default.School,
                                contentDescription = null,
                                tint = if (state.role == UserRole.ADMIN) Color(0xFFD97706) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (state.role == UserRole.ADMIN) "نوع الحساب: مسؤول (Admin)" else "نوع الحساب: طالب (Student)",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (state.role == UserRole.ADMIN) Color(0xFFD97706) else MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    if (state.role != UserRole.ADMIN) {
                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${state.enrolledSubjectIds.size}",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text("مادة مسجلة", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            Box(modifier = Modifier.width(1.dp).height(30.dp).background(MaterialTheme.colorScheme.outlineVariant))

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val yearDisplayName = state.academicYears.find { it.id == state.academicYear }?.name
                                    ?: CurriculumData.getYearName(state.academicYear)
                                Text(
                                    text = yearDisplayName.replace("السنة ", ""),
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text("السنة الدراسية", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            Box(modifier = Modifier.width(1.dp).height(30.dp).background(MaterialTheme.colorScheme.outlineVariant))

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val semDisplayName = state.semesters.find { it.id == state.semester }?.name
                                    ?: CurriculumData.getSemesterName(state.semester)
                                Text(
                                    text = semDisplayName.replace("الفصل ", ""),
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text("الفصل الحالي", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    if (state.role != UserRole.ADMIN) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "السنة الدراسية",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "تُبنى المواد المقترحة على سنتك وفصلك الحاليين في جامعة حمص.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            val availableYears = if (state.academicYears.isNotEmpty()) {
                                state.academicYears.map { it.id to "${it.id}" }
                            } else {
                                (1..5).map { it to "$it" }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                availableYears.forEach { (yr, label) ->
                                    val selected = state.academicYear == yr
                                    Button(
                                        onClick = { onYearChange(yr) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (selected) DeepNavy else MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(label, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "الفصل الدراسي",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            val availableSemesters = if (state.semesters.isNotEmpty()) {
                                state.semesters.map { it.id to it.name }
                            } else {
                                listOf(1 to "الفصل الأول", 2 to "الفصل الثاني")
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                availableSemesters.forEach { (sem, label) ->
                                    val selected = state.semester == sem
                                    Button(
                                        onClick = { onSemesterChange(sem) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (selected) DeepNavy else MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    ) {
                                        Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "المظهر",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { if (state.isDarkMode) onToggleDarkMode() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (!state.isDarkMode) DeepNavy else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (!state.isDarkMode) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Icon(Icons.Default.LightMode, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("فاتح", fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { if (!state.isDarkMode) onToggleDarkMode() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (state.isDarkMode) DeepNavy else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (state.isDarkMode) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Icon(Icons.Default.DarkMode, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("داكن", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Box(modifier = Modifier.padding(top = 8.dp)) {
                        Button(
                            onClick = onLogout,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("logout_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("تسجيل الخروج", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FilterPill(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) DeepNavy else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) DeepNavy else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}
