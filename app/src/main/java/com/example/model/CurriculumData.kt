package com.example.model

data class AcademicYear(
    val id: Int,
    val name: String
)

data class Semester(
    val id: Int,
    val name: String
)

data class Subject(
    val id: String,
    val name: String,
    val academicYear: Int = 1,
    val semester: Int = 1,
    val pricePoints: Int = 0,
    val description: String = "",
    val isPublished: Boolean = false,
    val createdAt: String = ""
) {
    val year: Int get() = academicYear
}

data class TopicItem(
    val id: String,
    val subjectId: String,
    val title: String,
    val order: Int,
    val summary: String,
    val detailedNotes: String,
    val durationMinutes: Int,
    val keyPoints: List<String>
)

data class ExamQuestion(
    val id: String,
    val questionNumber: Int,
    val text: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String
)

data class ExamPaper(
    val id: String,
    val subjectId: String,
    val title: String,
    val year: String,
    val semesterName: String,
    val questionsCount: Int,
    val durationMinutes: Int,
    val questions: List<ExamQuestion>
)

data class UnaskedQuestion(
    val id: String,
    val subjectId: String,
    val title: String,
    val topicName: String,
    val questionText: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String,
    val priority: String // "متوقع بقوة", "هام جداً", "تركيز امتحاني"
)

object CurriculumData {
    fun getYearName(year: Int): String = when (year) {
        1 -> "السنة الأولى"
        2 -> "السنة الثانية"
        3 -> "السنة الثالثة"
        4 -> "السنة الرابعة"
        5 -> "السنة الخامسة"
        else -> "السنة $year"
    }

    fun getSemesterName(semester: Int): String = when (semester) {
        1 -> "الفصل الأول"
        2 -> "الفصل الثاني"
        else -> "الفصل $semester"
    }

    val ALL_SUBJECTS = listOf(
        // السنة الأولى - الفصل الأول
        Subject("y1s1-01", "الكيمياء الصناعية", 1, 1),
        Subject("y1s1-02", "مبادئ عمل الحواسيب", 1, 1),
        Subject("y1s1-03", "الرسم والتمثيل الهندسي", 1, 1),
        Subject("y1s1-04", "اللغة الأجنبية 1", 1, 1),
        Subject("y1s1-05", "الفيزياء", 1, 1),
        Subject("y1s1-06", "الرياضيات 1", 1, 1),

        // السنة الأولى - الفصل الثاني
        Subject("y1s2-01", "الرياضيات 2", 1, 2),
        Subject("y1s2-02", "اللغة العربية", 1, 2),
        Subject("y1s2-03", "أسس الهندسة الكهربائية", 1, 2),
        Subject("y1s2-04", "البرمجة 1", 1, 2),
        Subject("y1s2-05", "اللغة الأجنبية 2", 1, 2),
        Subject("y1s2-06", "الميكانيك الهندسي 1", 1, 2),

        // السنة الثانية - الفصل الأول
        Subject("y2s1-01", "هندسة الإنتاج", 2, 1),
        Subject("y2s1-02", "الميكانيك الهندسي 2", 2, 1),
        Subject("y2s1-03", "اللغة الأجنبية 3", 2, 1),
        Subject("y2s1-04", "مقاومة المواد وخواصها", 2, 1),
        Subject("y2s1-05", "البرمجة 2", 2, 1),
        Subject("y2s1-06", "الرياضيات 3", 2, 1),

        // السنة الثانية - الفصل الثاني
        Subject("y2s2-01", "الرياضيات 4", 2, 2),
        Subject("y2s2-02", "أسس الهندسة الإلكترونية", 2, 2),
        Subject("y2s2-03", "نظم التحكم", 2, 2),
        Subject("y2s2-04", "اللغة الأجنبية 4", 2, 2),
        Subject("y2s2-05", "أسس الهندسة الحرارية", 2, 2),
        Subject("y2s2-06", "حساسات ومجسات", 2, 2),
        Subject("y2s2-07", "ميكانيك الموائع", 2, 2),

        // السنة الثالثة - الفصل الأول
        Subject("y3s1-01", "نظرية الآلات", 3, 1),
        Subject("y3s1-02", "تقنية البصريات", 3, 1),
        Subject("y3s1-03", "الشبكات الحاسوبية", 3, 1),
        Subject("y3s1-04", "القياسات وأجهزة القياس", 3, 1),
        Subject("y3s1-05", "بنية الحاسوب", 3, 1),
        Subject("y3s1-06", "الآلات الكهربائية", 3, 1),

        // السنة الثالثة - الفصل الثاني
        Subject("y3s2-01", "هندسة الدارات الرقمية", 3, 2),
        Subject("y3s2-02", "تصميم الأنظمة الميكاترونية", 3, 2),
        Subject("y3s2-03", "الإلكترونيات الصناعية", 3, 2),
        Subject("y3s2-04", "التصميم باستخدام الحاسوب", 3, 2),
        Subject("y3s2-05", "متحكمات منطقية قابلة للبرمجة PLC", 3, 2),
        Subject("y3s2-06", "تصميم الآلات", 3, 2),

        // السنة الرابعة - الفصل الأول
        Subject("y4s1-01", "القيادة الكهربائية", 4, 1),
        Subject("y4s1-02", "التحكم الآلي", 4, 1),
        Subject("y4s1-03", "الصيانة والأمن الصناعي", 4, 1),
        Subject("y4s1-04", "آلات النقل والرفع", 4, 1),
        Subject("y4s1-05", "إدارة الجودة", 4, 1),
        Subject("y4s1-06", "النمذجة والمحاكاة", 4, 1),

        // السنة الرابعة - الفصل الثاني
        Subject("y4s2-01", "الأتمتة الصناعية", 4, 2),
        Subject("y4s2-02", "معالجة الإشارة", 4, 2),
        Subject("y4s2-03", "متحكمات صغرية", 4, 2),
        Subject("y4s2-04", "ديناميك آلات واهتزازات", 4, 2),
        Subject("y4s2-05", "آلات تشكيل المعادن", 4, 2),
        Subject("y4s2-06", "التدفئة والتكييف", 4, 2),

        // السنة الخامسة - الفصل الأول
        Subject("y5s1-01", "أنظمة الاتصال الصناعية", 5, 1),
        Subject("y5s1-02", "ترشيد استهلاك الطاقة والبيئة", 5, 1),
        Subject("y5s1-03", "نظم التحكم في الزمن الحقيقي", 5, 1),
        Subject("y5s1-04", "نظم القياسات المعلوماتية", 5, 1),
        Subject("y5s1-05", "تطبيقات صناعية", 5, 1),

        // السنة الخامسة - الفصل الثاني
        Subject("y5s2-01", "الذكاء الصنعي", 5, 2),
        Subject("y5s2-02", "التنظيم الصناعي", 5, 2),
        Subject("y5s2-03", "الاتصالات", 5, 2),
        Subject("y5s2-04", "معالجة الصور", 5, 2),
        Subject("y5s2-05", "تطبيقات PLC في الروبوت", 5, 2)
    )

    const val TOTAL_SUBJECTS_COUNT = 59

    fun getSubjectById(id: String): Subject? {
        return ALL_SUBJECTS.firstOrNull { it.id == id }
    }

    // ==============================================================================
    // 1. شرح المادة (Course Topics/Lectures) - فارغ حالياً ويتم إضافته من المشرف يدوياً
    // ==============================================================================
    fun getTopicsForSubject(subjectId: String): List<TopicItem> {
        return emptyList()
    }

    // ==============================================================================
    // 2. اسئلة الدورات (Past Exam Papers & Questions) - فارغ حالياً
    // ==============================================================================
    fun getExamPapersForSubject(subjectId: String): List<ExamPaper> {
        return emptyList()
    }

    // ==============================================================================
    // 3. الأسئلة التي لم ترد (Unasked & High-Probability Questions) - فارغ حالياً
    // ==============================================================================
    fun getUnaskedQuestionsForSubject(subjectId: String): List<UnaskedQuestion> {
        return emptyList()
    }
}
