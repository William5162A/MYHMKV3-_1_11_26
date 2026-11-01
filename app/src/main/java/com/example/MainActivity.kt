package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.CurriculumData
import com.example.ui.screens.BrowseCoursesScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LoginScreen
import com.example.ui.screens.MyCoursesScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.RegisterScreen
import com.example.ui.screens.SubjectDetailScreen
import com.example.ui.theme.CairoFontFamily
import com.example.ui.theme.DeepNavy
import com.example.ui.theme.EmeraldMint
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ReemKufiFontFamily
import com.example.viewmodel.AppViewModel

class MainActivity : ComponentActivity() {
    private var lastHandledAuthUri: String? = null
    private val appViewModel: AppViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[AppViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.data.SessionManager.init(applicationContext)
        enableEdgeToEdge()
        handleAuthIntent(intent)

        setContent {
            val viewModel: AppViewModel = appViewModel
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            MyApplicationTheme(darkTheme = state.isDarkMode) {
                // Enforce RTL for proper Arabic layout and bottom navigation order (Right to Left)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        if (!state.isLoggedIn) {
                            var isRegistering by rememberSaveable { mutableStateOf(false) }

                            if (isRegistering) {
                                RegisterScreen(
                                    onSignUp = { email, pass, username, displayName, onResult ->
                                        viewModel.signUp(email, pass, username, displayName, onResult)
                                    },
                                    onBackToLogin = {
                                        viewModel.clearLoginError()
                                        isRegistering = false
                                    },
                                    isDarkMode = state.isDarkMode,
                                    onToggleDarkMode = { viewModel.toggleDarkMode() }
                                )
                            } else {
                                LoginScreen(
                                    onLogin = { u, p -> viewModel.login(u, p) },
                                    errorMessage = state.loginError,
                                    isDarkMode = state.isDarkMode,
                                    onToggleDarkMode = { viewModel.toggleDarkMode() },
                                    isLoading = state.isLoggingIn,
                                    onRegisterClick = {
                                        viewModel.clearLoginError()
                                        isRegistering = true
                                    }
                                )
                            }
                        } else {
                            if (state.selectedSubjectId != null) {
                                val subject = state.subjects.firstOrNull { it.id == state.selectedSubjectId }
                                    ?: CurriculumData.getSubjectById(state.selectedSubjectId!!)
                                val isSubjectPublished = subject?.isPublished == true || state.addedSubjectIds.contains(subject?.id)
                                val canAccess = state.role == com.example.viewmodel.UserRole.ADMIN || isSubjectPublished

                                if (subject != null && canAccess) {
                                    SubjectDetailScreen(
                                        subject = subject,
                                        state = state,
                                        viewModel = viewModel,
                                        onBack = { viewModel.selectSubject(null) },
                                        onToggleTopicCompletion = { viewModel.toggleTopicCompletion(it) },
                                        onAddCustomTopic = { sId, title, desc, files -> viewModel.addCustomTopic(sId, title, desc, files) },
                                        onRemoveCustomTopic = { sId, tId -> viewModel.removeCustomTopic(sId, tId) },
                                        onUpdateSupabaseConfig = { cfg -> viewModel.updateSupabaseConfig(cfg) }
                                    )
                                } else {
                                    if (subject != null && !canAccess) {
                                        viewModel.setLoginError("عذراً، هذه المادة غير منشورة للطلاب حالياً.")
                                    }
                                    viewModel.selectSubject(null)
                                }
                            } else {
                                MainContentScreen(viewModel = viewModel)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthIntent(intent)
    }

    private fun handleAuthIntent(incomingIntent: Intent?) {
        val uri = incomingIntent?.data ?: return
        if (uri.scheme.equals("midad", ignoreCase = true) && uri.host.equals("auth-callback", ignoreCase = true)) {
            val uriString = uri.toString()
            if (lastHandledAuthUri == uriString) {
                // Prevent duplicate processing on activity recreation / rotation
                return
            }
            lastHandledAuthUri = uriString
            // Clear data from intent so recreation won't process it again
            incomingIntent.data = null
            appViewModel.handleAuthCallback(uri)
        }
    }
}

data class NavTabItem(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContentScreen(
    viewModel: AppViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Handle back button: if not on Home tab (0), return to Home
    BackHandler(enabled = state.currentTab != 0) {
        viewModel.setCurrentTab(0)
    }

    // 4 Sections ordered in RTL from right to left:
    // 1. الرئيسية
    // 2. موادي / المواد المضافة
    // 3. تصفح المواد / كل المواد
    // 4. ملفي الشخصي
    val isAdmin = state.role == com.example.viewmodel.UserRole.ADMIN
    val tabs = remember(isAdmin) {
        listOf(
            NavTabItem("الرئيسية", Icons.Filled.Home, Icons.Outlined.Home, "nav_tab_home"),
            NavTabItem(if (isAdmin) "المواد المضافة" else "موادي", Icons.Filled.ShoppingBag, Icons.Outlined.ShoppingBag, "nav_tab_my_courses"),
            NavTabItem(if (isAdmin) "كل المواد" else "تصفح المواد", Icons.Filled.Book, Icons.Outlined.Book, "nav_tab_browse"),
            NavTabItem("ملفي الشخصي", Icons.Filled.Person, Icons.Outlined.Person, "nav_tab_profile")
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("main_scaffold"),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DeepNavy),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "م",
                                color = EmeraldMint,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                        Text(
                            text = "مِداد",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = ReemKufiFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 23.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    // Supabase cloud sync button
                    IconButton(
                        onClick = { viewModel.syncWithSupabase() },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .testTag("topbar_supabase_sync")
                    ) {
                        if (state.isSyncingWithSupabase) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = EmeraldMint
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "مزامنة سحابية مع Supabase",
                                tint = if (state.supabaseSyncStatusMessage != null) EmeraldMint else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = { viewModel.toggleDarkMode() },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .testTag("topbar_theme_toggle")
                    ) {
                        Icon(
                            imageVector = if (state.isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "تبديل المظهر",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("bottom_nav_bar"),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val selected = state.currentTab == index
                        val interactionSource = remember { MutableInteractionSource() }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (selected) {
                                        if (state.isDarkMode) Color(0xFF1B2A4A) else DeepNavy
                                    } else Color.Transparent
                                )
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = ripple()
                                ) {
                                    viewModel.setCurrentTab(index)
                                }
                                .testTag(tab.testTag),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.title,
                                    tint = if (selected) {
                                        if (state.isDarkMode) EmeraldMint else Color.White
                                    } else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = tab.title,
                                    fontSize = 10.5.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) {
                                        if (state.isDarkMode) EmeraldMint else Color.White
                                    } else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Crossfade(
                targetState = state.currentTab,
                label = "tab_crossfade"
            ) { currentTab ->
                when (currentTab) {
                    0 -> HomeScreen(
                        state = state,
                        onNavigateToBrowse = { viewModel.setCurrentTab(2) },
                        onNavigateToMyCourses = { viewModel.setCurrentTab(1) },
                        onNavigateToProfile = { viewModel.setCurrentTab(3) },
                        onSelectSubject = { subjectId -> viewModel.selectSubject(subjectId) }
                    )
                    1 -> MyCoursesScreen(
                        state = state,
                        onSelectSubject = { subjectId -> viewModel.selectSubject(subjectId) },
                        onNavigateToBrowse = { viewModel.setCurrentTab(2) },
                        onTogglePublishSubject = { subjectId -> viewModel.togglePublishSubject(subjectId) }
                    )
                    2 -> BrowseCoursesScreen(
                        state = state,
                        onSearchChange = { q -> viewModel.setSearchQuery(q) },
                        onFilterYearChange = { y -> viewModel.setFilterYear(y) },
                        onFilterSemesterChange = { s -> viewModel.setFilterSemester(s) },
                        onToggleEnroll = { id -> viewModel.toggleEnroll(id) },
                        onTogglePublishSubject = { id -> viewModel.togglePublishSubject(id) },
                        onSelectSubject = { id -> viewModel.selectSubject(id) },
                        onRetry = { viewModel.syncWithSupabase() }
                    )
                    3 -> ProfileScreen(
                        state = state,
                        onToggleDarkMode = { viewModel.toggleDarkMode() },
                        onYearChange = { y -> viewModel.setAcademicYear(y) },
                        onSemesterChange = { s -> viewModel.setSemester(s) },
                        onLogout = { viewModel.logout() }
                    )
                }
            }
        }
    }
}
