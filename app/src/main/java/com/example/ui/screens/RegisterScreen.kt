package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AuthResult
import com.example.ui.theme.DeepNavy
import com.example.ui.theme.EmeraldMint
import com.example.ui.theme.ReemKufiFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    onSignUp: (email: String, pass: String, username: String, displayName: String, onResult: (AuthResult) -> Unit) -> Unit,
    onBackToLogin: () -> Unit,
    isDarkMode: Boolean,
    onToggleDarkMode: () -> Unit
) {
    BackHandler {
        onBackToLogin()
    }

    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var successDialogMessage by remember { mutableStateOf("") }

    // Username regex: Latin letters, digits, underscores, 3-30 chars
    val usernamePattern = remember { Regex("^[a-zA-Z0-9_]{3,30}$") }
    // Email regex for client-side validation
    val emailPattern = remember { Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$") }

    fun validateAndSubmit() {
        val cleanUser = username.trim()
        val cleanEmail = email.trim()
        val cleanPass = password.trim()
        val cleanConfirm = confirmPassword.trim()

        if (cleanUser.isEmpty()) {
            errorMessage = "يرجى إدخال اسم المستخدم."
            return
        }
        if (!usernamePattern.matches(cleanUser)) {
            errorMessage = "يجب أن يتكون اسم المستخدم من أحرف إنجليزية وأرقام وشرطة سفلية فقط (_) وبطول بين 3 و 30 محرفًا."
            return
        }
        if (cleanEmail.isEmpty()) {
            errorMessage = "يرجى إدخال البريد الإلكتروني."
            return
        }
        if (!emailPattern.matches(cleanEmail)) {
            errorMessage = "صيغة البريد الإلكتروني غير صحيحة (مثال: student@email.com)."
            return
        }
        if (cleanPass.isEmpty()) {
            errorMessage = "يرجى إدخال كلمة المرور."
            return
        }
        if (cleanPass.length < 6) {
            errorMessage = "يجب ألا تقل كلمة المرور عن 6 أحرف أو أرقام."
            return
        }
        if (cleanConfirm.isEmpty()) {
            errorMessage = "يرجى تأكيد كلمة المرور."
            return
        }
        if (cleanPass != cleanConfirm) {
            errorMessage = "كلمتا المرور غير متطابقتين. يرجى التأكد وإعادة المحاولة."
            return
        }

        errorMessage = null
        isSubmitting = true

        onSignUp(cleanEmail, cleanPass, cleanUser, cleanUser) { result ->
            isSubmitting = false
            when (result) {
                is AuthResult.RequireEmailConfirmation -> {
                    successDialogMessage = result.message
                    showSuccessDialog = true
                }
                is AuthResult.Success -> {
                    successDialogMessage = "تم إنشاء حسابك بنجاح! يرجى تسجيل الدخول للبدء باستخدام المنصة."
                    showSuccessDialog = true
                }
                is AuthResult.Error -> {
                    errorMessage = result.message
                }
            }
        }
    }

    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = {
                showSuccessDialog = false
                onBackToLogin()
            },
            icon = {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(EmeraldMint.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MarkEmailRead,
                        contentDescription = null,
                        tint = if (isDarkMode) EmeraldMint else Color(0xFF047857),
                        modifier = Modifier.size(28.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "تأكيد البريد الإلكتروني",
                    fontFamily = ReemKufiFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Text(
                    text = successDialogMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSuccessDialog = false
                        onBackToLogin()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("register_dialog_confirm_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDarkMode) EmeraldMint else DeepNavy,
                        contentColor = if (isDarkMode) DeepNavy else Color.White
                    )
                ) {
                    Text(
                        text = "الانتقال إلى تسجيل الدخول",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("register_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(DeepNavy),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "م",
                                color = EmeraldMint,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                        }
                        Text(
                            text = "مِداد",
                            fontFamily = ReemKufiFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackToLogin,
                        modifier = Modifier.testTag("register_topbar_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "العودة لتسجيل الدخول",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onToggleDarkMode,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("register_theme_toggle")
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                                    contentDescription = if (isDarkMode) "تفعيل الوضع الفاتح" else "تفعيل الوضع الداكن",
                                    tint = if (isDarkMode) EmeraldMint else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Decorative background glowing accents
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 100.dp, y = (-60).dp)
                    .clip(CircleShape)
                    .background(EmeraldMint.copy(alpha = if (isDarkMode) 0.08f else 0.12f))
            )
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .align(Alignment.BottomStart)
                    .offset(x = (-100).dp, y = 100.dp)
                    .clip(CircleShape)
                    .background(DeepNavy.copy(alpha = if (isDarkMode) 0.15f else 0.06f))
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(EmeraldMint.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PersonAdd,
                                contentDescription = null,
                                tint = if (isDarkMode) EmeraldMint else Color(0xFF059669),
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "انضم إلى منصة مِداد",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isDarkMode) EmeraldMint else Color(0xFF059669)
                            )
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "إنشاء حساب جديد",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = ReemKufiFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 26.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "أنشئ حسابك كطالب جامعي للوصول إلى المواد والمحاضرات.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // 1. Username field
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "اسم المستخدم (Username)",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            OutlinedTextField(
                                value = username,
                                onValueChange = {
                                    username = it
                                    if (errorMessage != null) errorMessage = null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("register_username_input"),
                                placeholder = { Text("مثال: ahmad_sy") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Ascii,
                                    imeAction = ImeAction.Next
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = EmeraldMint,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 2. Email field
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "البريد الإلكتروني",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            OutlinedTextField(
                                value = email,
                                onValueChange = {
                                    email = it
                                    if (errorMessage != null) errorMessage = null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("register_email_input"),
                                placeholder = { Text("example@email.com") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Email,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Next
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = EmeraldMint,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 3. Password field
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "كلمة المرور",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = {
                                    password = it
                                    if (errorMessage != null) errorMessage = null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("register_password_input"),
                                placeholder = { Text("6 محارف على الأقل") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (passwordVisible) "إخفاء كلمة المرور" else "إظهار كلمة المرور",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Next
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = EmeraldMint,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 4. Confirm Password field
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "تأكيد كلمة المرور",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            OutlinedTextField(
                                value = confirmPassword,
                                onValueChange = {
                                    confirmPassword = it
                                    if (errorMessage != null) errorMessage = null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("register_confirm_password_input"),
                                placeholder = { Text("أعد إدخال كلمة المرور") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                trailingIcon = {
                                    IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                        Icon(
                                            imageVector = if (confirmPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (confirmPasswordVisible) "إخفاء تأكيد كلمة المرور" else "إظهار تأكيد كلمة المرور",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        if (!isSubmitting) validateAndSubmit()
                                    }
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = EmeraldMint,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                )
                            )
                        }

                        if (!errorMessage.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = errorMessage!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Submit Button
                        Button(
                            onClick = { validateAndSubmit() },
                            enabled = !isSubmitting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("register_submit_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDarkMode) EmeraldMint else DeepNavy,
                                contentColor = if (isDarkMode) DeepNavy else Color.White,
                                disabledContainerColor = (if (isDarkMode) EmeraldMint else DeepNavy).copy(alpha = 0.6f),
                                disabledContentColor = (if (isDarkMode) DeepNavy else Color.White).copy(alpha = 0.7f)
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = if (isDarkMode) DeepNavy else Color.White
                                )
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "إنشاء الحساب",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        Spacer(modifier = Modifier.height(12.dp))

                        // Back to login button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "لديك حساب بالفعل؟",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = onBackToLogin,
                                modifier = Modifier.testTag("register_back_to_login_button")
                            ) {
                                Text(
                                    text = "تسجيل الدخول",
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDarkMode) EmeraldMint else Color(0xFF047857)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.School,
                        contentDescription = null,
                        tint = EmeraldMint,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "جامعة حمص · كلية الهندسة الميكانيكية والكهربائية (ميكاترونيك)",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
