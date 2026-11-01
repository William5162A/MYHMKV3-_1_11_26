package com.example.model

import com.example.BuildConfig

data class TopicAttachment(
    val id: String,
    val name: String,
    val sizeFormatted: String,
    val supabaseUrl: String,
    val localUri: String? = null,
    val mimeType: String = "application/pdf",
    val uploadDate: String = ""
)

data class AdminTopic(
    val id: String,
    val subjectId: String,
    val title: String,
    val description: String = "",
    val attachments: List<TopicAttachment> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

data class SupabaseConfig(
    val projectUrl: String = BuildConfig.SUPABASE_URL.trim().trimEnd('/'),
    val anonKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim(),
    val bucketName: String = "subject-files"
)
