package com.example.ui.screens

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import com.example.ui.theme.DeepNavy
import com.example.ui.theme.EmeraldMint
import com.example.ui.theme.ReemKufiFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLogin: (String, String) -> Unit,
    errorMessage: String?,
    isDarkMode: Boolean,
    onToggleDarkMode: () -> Unit,
    isLoading: Boolean = false,
    onRegisterClick: () -> Unit = {},
    onForgotPasswordClick: () -> Unit = {}
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("login_screen"),
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
                actions = {
                    IconButton(
                        onClick = onToggleDarkMode,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("login_theme_toggle")
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
                                    imageVector = if (isDarkMode) {
                                        Icons.Default.LightMode
                                    } else {
                                        Icons.Default.DarkMode
                                    },
                                    contentDescription = if (isDarkMode) {
                                        "تفعيل الوضع الفاتح"
                                    } else {
                                        "تفعيل الوضع الداكن"
                                    },
                                    tint = if (isDarkMode) {
                                        EmeraldMint
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
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
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 100.dp, y = (-60).dp)
                    .clip(CircleShape)
                    .background(
                        EmeraldMint.copy(
                            alpha = if (isDarkMode) 0.08f else 0.12f
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .align(Alignment.BottomStart)
                    .offset(x = (-100).dp, y = 100.dp)
                    .clip(CircleShape)
                    .background(
                        DeepNavy.copy(
                            alpha = if (isDarkMode) 0.15f else 0.06f
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
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
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = 2.dp
                    )
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
                                imageVector = Icons.Default.School,
                                contentDescription = null,
                                tint = if (isDarkMode) {
                                    EmeraldMint
                                } else {
                                    Color(0xFF059669)
                                },
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "مرحبًا بك في منصة مِداد",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isDarkMode) {
                                    EmeraldMint
                                } else {
                                    Color(0xFF059669)
                                }
                            )
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "تسجيل الدخول",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = ReemKufiFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 26.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "تابع رحلتك التعليمية وسجّل دخولك إلى حسابك الجامعي.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "البريد الإلكتروني",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("login_email_input"),
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

                        Spacer(modifier = Modifier.height(14.dp))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "كلمة المرور",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("login_password_input"),
                                placeholder = { Text("أدخل كلمة المرور") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { passwordVisible = !passwordVisible }
                                    ) {
                                        Icon(
                                            imageVector = if (passwordVisible) {
                                                Icons.Default.VisibilityOff
                                            } else {
                                                Icons.Default.Visibility
                                            },
                                            contentDescription = if (passwordVisible) {
                                                "إخفاء كلمة المرور"
                                            } else {
                                                "إظهار كلمة المرور"
                                            },
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                visualTransformation = if (passwordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { onLogin(email.trim(), password) }
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = EmeraldMint,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                )
                            )
                        }

                        TextButton(
                            onClick = onForgotPasswordClick,
                            modifier = Modifier
                                .align(Alignment.Start)
                                .testTag("login_forgot_password")
                        ) {
                            Text(
                                text = "نسيت كلمة المرور؟",
                                color = if (isDarkMode) {
                                    EmeraldMint
                                } else {
                                    Color(0xFF047857)
                                }
                            )
                        }

                        if (!errorMessage.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            val isSuccessNotice = errorMessage.startsWith("تم") || errorMessage.contains("بنجاح") || errorMessage.contains("تأكيد")
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSuccessNotice) {
                                        EmeraldMint.copy(alpha = 0.15f)
                                    } else {
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                                    }
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSuccessNotice) {
                                        EmeraldMint.copy(alpha = 0.5f)
                                    } else {
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                                    }
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isSuccessNotice) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = if (isSuccessNotice) {
                                            if (isDarkMode) EmeraldMint else Color(0xFF047857)
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = errorMessage,
                                        color = if (isSuccessNotice) {
                                            if (isDarkMode) EmeraldMint else Color(0xFF047857)
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                        textAlign = TextAlign.Start,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { onLogin(email.trim(), password) },
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("login_submit_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDarkMode) {
                                    EmeraldMint
                                } else {
                                    DeepNavy
                                },
                                contentColor = if (isDarkMode) {
                                    DeepNavy
                                } else {
                                    Color.White
                                },
                                disabledContainerColor = (if (isDarkMode) EmeraldMint else DeepNavy).copy(alpha = 0.6f),
                                disabledContentColor = (if (isDarkMode) DeepNavy else Color.White).copy(alpha = 0.7f)
                            ),
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = 2.dp
                            )
                        ) {
                            if (isLoading) {
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
                                        text = "دخول إلى المنصة",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ليس لديك حساب؟",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = onRegisterClick,
                                modifier = Modifier.testTag("login_register_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PersonAdd,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "إنشاء حساب",
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDarkMode) {
                                        EmeraldMint
                                    } else {
                                        Color(0xFF047857)
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

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
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}