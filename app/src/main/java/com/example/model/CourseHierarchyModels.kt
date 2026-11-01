package com.example.model

data class CourseUnit(
    val id: String,
    val subjectId: String,
    val title: String,
    val description: String?,
    val orderIndex: Int,
    val isPublished: Boolean,
    val createdAt: String,
    val updatedAt: String
)

data class CourseChapter(
    val id: String,
    val unitId: String,
    val subjectId: String,
    val title: String,
    val description: String?,
    val orderIndex: Int,
    val isPublished: Boolean,
    val createdAt: String,
    val updatedAt: String
)

data class CourseLesson(
    val id: String,
    val unitId: String,
    val subjectId: String,
    val chapterId: String?,
    val title: String,
    val summary: String?,
    val durationMinutes: Int?,
    val orderIndex: Int,
    val isPublished: Boolean,
    val createdAt: String,
    val updatedAt: String
)

data class LessonPdf(
    val id: String,
    val lessonId: String,
    val subjectId: String,
    val title: String,
    val filePath: String,
    val fileSizeBytes: Long?,
    val mimeType: String,
    val orderIndex: Int,
    val isPublished: Boolean,
    val createdAt: String,
    val updatedAt: String
)

data class PastExamPaper(
    val id: String,
    val subjectId: String,
    val title: String,
    val academicYearLabel: String?,
    val durationMinutes: Int?,
    val isPublished: Boolean,
    val createdAt: String,
    val updatedAt: String
)

data class ExamQuestionEntity(
    val id: String,
    val examPaperId: String,
    val questionNumber: Int,
    val questionText: String,
    val options: List<String>,
    val correctOptionIndex: Int,
    val explanation: String?,
    val createdAt: String,
    val updatedAt: String
)

data class UnaskedQuestionEntity(
    val id: String,
    val subjectId: String,
    val unitId: String?,
    val chapterId: String?,
    val title: String?,
    val questionText: String,
    val options: List<String>,
    val correctOptionIndex: Int,
    val explanation: String?,
    val priority: String,
    val isPublished: Boolean,
    val createdAt: String,
    val updatedAt: String
)

data class LessonProgress(
    val id: String,
    val userId: String,
    val lessonId: String,
    val isCompleted: Boolean,
    val completedAt: String?,
    val updatedAt: String
)

data class SubjectPurchase(
    val id: String,
    val userId: String,
    val subjectId: String,
    val pricePointsPaid: Int,
    val purchasedAt: String
)

data class SubjectEnrollment(
    val id: String,
    val userId: String,
    val subjectId: String,
    val purchaseId: String?,
    val purchasedAt: String,
    val expiresAt: String?,
    val createdAt: String,
    val updatedAt: String
)

data class PlatformSettings(
    val id: Int,
    val materialsExpiryDate: String?,
    val updatedAt: String,
    val updatedBy: String?
)
