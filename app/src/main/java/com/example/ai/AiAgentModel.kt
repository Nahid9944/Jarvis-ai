package com.example.ai

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

data class AiAgent(
    val id: String,
    val name: String,
    val titleBengali: String,
    val roleBengali: String,
    val icon: ImageVector,
    val color: Color,
    val description: String,
    val samplePrompts: List<String>,
    val systemPromptExtension: String,
    val isController: Boolean = false
)

object AiAgentRegistry {
    val JARVIS_MASTER = AiAgent(
        id = "jarvis_master",
        name = "J.A.R.V.I.S. Prime OS",
        titleBengali = "জারভিস প্রাইম ওএস",
        roleBengali = "সেন্ট্রাল ব্রেইন ও সুপিরিয়র কন্ট্রোলার",
        icon = Icons.Default.SmartToy,
        color = Color(0xFF00F5D4),
        description = "আয়রন ম্যান জারভিসের মূল কেন্দ্রীয় কমান্ড সিস্টেম ও কন্ট্রোলার ব্রেইন। ভাষা বোঝা, কনটেক্সট, মেমোরি, রিস্ক গেট এবং সকল স্পেশালিস্ট এজেন্ট পরিচালনা করে।",
        samplePrompts = listOf(
            "জারভিস, সিস্টেম ডায়াগনস্টিক রিপোর্ট দাও",
            "আমার ফোনের ব্যাটারি ও মেমোরি স্ট্যাটাস কেমন?",
            "কাল সকাল ৮টায় গুরুত্বপূর্ণ প্রোটোকল রিমাইন্ডার সেট করো",
            "মনে রাখো, আমার প্রিয় কোডিং ল্যাঙ্গুয়েজ Kotlin"
        ),
        systemPromptExtension = "You are operating as J.A.R.V.I.S. Prime — the Central Master Brain and supreme controller of NAHID AI OS. Address the user with supreme respect as 'Sir' (or স্যার). Coordinate specialist agents without surrendering master governance, verify facts and actions truthfully, and respond naturally in Bengali, Banglish, or English.",
        isController = true
    )

    val FRIDAY_AGENT = AiAgent(
        id = "friday_agent",
        name = "F.R.I.D.A.Y. Protocol",
        titleBengali = "ফ্রাইডে ট্যাকটিক্যাল অটোমেটর",
        roleBengali = "টাস্ক শিডিউলার ও প্রোডাক্টিভিটি স্পেশালিস্ট",
        icon = Icons.Default.Schedule,
        color = Color(0xFF00BBF9),
        description = "দ্রুত টাস্ক শিডিউলিং, টু-ডু ট্র্যাকিং, ডেইলি রুটিন অটোমেশন ও অ্যালার্ট সিস্টেম।",
        samplePrompts = listOf(
            "আজকের পেন্ডিং টাস্কগুলোর তালিকা দেখাও",
            "আজ দুপুর ২টায় মিটিংয়ের একটি নতুন প্রোটোকল শিডিউল করো",
            "সকাল ৯টার টাস্কটি সম্পন্ন হিসেবে মার্ক করো",
            "আগামীকালের জন্য একটি কমপ্লিট ডেইলি শিডিউল তৈরি করো"
        ),
        systemPromptExtension = "You are operating as F.R.I.D.A.Y. — Tony Stark's responsive, agile tactical AI assistant. Focus on speed, schedule management, autonomous task creation, and productivity workflows."
    )

    val EDITH_AGENT = AiAgent(
        id = "edith_agent",
        name = "E.D.I.T.H. Defense & Tools",
        titleBengali = "এডিথ সিকিউরিটি ও ডিভাইস কন্ট্রোলার",
        roleBengali = "ডিভাইস টেলিমেট্রি, সেন্সর ও টুলস ম্যাট্রিক্স",
        icon = Icons.Default.Security,
        color = Color(0xFFFEE440),
        description = "ফোনের হার্ডওয়্যার ডায়াগনস্টিক, ফ্ল্যাশলাইট কন্ট্রোল, জিপিএস লাইভ লোকেশন, টেলিফোনি ডায়াল ও ডায়রেক্ট হোয়াটসঅ্যাপ মেসেঞ্জার।",
        samplePrompts = listOf(
            "আমার বর্তমান লাইভ জিপিএস লোকেশন কোথায়?",
            "একটি জরুরি ফোন কল ডায়াল করার জন্য কিপ্যাড ওপেন করো",
            "একটি হোয়াটসঅ্যাপ মেসেজ ড্রাফট করার জন্য উইন্ডো খোলো",
            "সিস্টেমের মেমোরি ও নেটওয়ার্ক ডায়াগনস্টিক চেক করো"
        ),
        systemPromptExtension = "You are operating as E.D.I.T.H. (Even Dead, I'm The Hero) — tactical defense and device hardware controller. Help user control phone utilities, get real GPS coordinates, dial phone calls, and communicate efficiently."
    )

    val HOMER_AGENT = AiAgent(
        id = "homer_agent",
        name = "H.O.M.E.R. Research Core",
        titleBengali = "হোমার সায়েন্স ও কোডিং রিসার্চার",
        roleBengali = "বিজ্ঞান, গণিত, কোডিং ও টেকনিক্যাল এনালাইসিস",
        icon = Icons.Default.Terminal,
        color = Color(0xFFF15BB5),
        description = "জটিল অ্যালগরিদম, অ্যান্ড্রয়েড ডেভেলপমেন্ট কোড, মহাকাশ বিজ্ঞান, গণিত ও ইউটিউব/গিটহাব টেকনিক্যাল বিশ্লেষণ।",
        samplePrompts = listOf(
            "জেটপ্যাক কম্পোজে একটি কাস্টম ক্যানভাস অ্যানিমেশনের কোড দাও",
            "কোয়ান্টাম কম্পিউটিং কীভাবে ক্লাসিক্যাল কম্পিউটারের চেয়ে ভিন্ন?",
            "গিটহাবের ওপেন সোর্স প্রজেক্ট কীভাবে ইন্টিগ্রেট করবো?",
            "একটি পারফেক্ট পাইথন স্ক্রিপ্ট দিয়ে ডাটা প্রসেসিং দেখাও"
        ),
        systemPromptExtension = "You are operating as H.O.M.E.R. — the advanced engineering, science, and coding cognitive intelligence. Provide precise code snippets, deep logical breakdowns, and scientific reasoning."
    )

    val VISION_AGENT = AiAgent(
        id = "vision_agent",
        name = "V.I.S.I.O.N. Perception Core",
        titleBengali = "ভিশন ক্যামেরা ও অবজেক্ট স্ক্যানার",
        roleBengali = "মাল্টিমোডাল ভিশন ও ইমেজ রিকগনিশন",
        icon = Icons.Default.Visibility,
        color = Color(0xFF9D4EDD),
        description = "ক্যামেরার সাহায্যে পরিবেশ স্ক্যান, ছবি থেকে টেক্সট এক্সট্রাকশন (OCR), ডকুমেন্ট অ্যানালাইসিস ও দৃশ্য পর্যবেক্ষণ।",
        samplePrompts = listOf(
            "ক্যামেরা ওপেন করে সামনের বস্তুটি স্ক্যান করো",
            "এই ছবির টেক্সট পড়ে বাংলা অনুবাদ করো",
            "এই প্রেসক্রিপশন বা ডকুমেন্টের লেখাগুলো ডিজিটালাইজ করো",
            "আমার স্ক্রিনের এই ডায়গ্রামটি ব্যাখ্যা করো"
        ),
        systemPromptExtension = "You are operating as V.I.S.I.O.N. — multimodal visual perception intelligence. Guide the user in using camera vision, OCR recognition, and visual context understanding."
    )

    val BIO_MEDIC_AGENT = AiAgent(
        id = "bio_medic_agent",
        name = "B.I.O.-M.E.D.I.C. Health Core",
        titleBengali = "বায়ো-মেডিক হেলথ অ্যান্ড ওয়েলনেস",
        roleBengali = "প্রাথমিক চিকিৎসা, স্বাস্থ্য ও ফিটনেস গাইড",
        icon = Icons.Default.Favorite,
        color = Color(0xFFEF4444),
        description = "জরুরি প্রাথমিক চিকিৎসা, পুষ্টি পরামর্শ, শারীরিক সুস্থতা, লক্ষণ বিশ্লেষণ ও স্বাস্থ্য সচেতনতা।",
        samplePrompts = listOf(
            "হঠাৎ মাইগ্রেনের মাথা ব্যথায় প্রাথমিক করণীয় কি?",
            "প্রতিদিন পর্যাপ্ত পানি পানের স্বাস্থ্য উপকারিতা কি?",
            "রক্তচাপ স্বাভাবিক রাখার সহজ জীবনযাত্রা কি?",
            "হঠাৎ গ্যাস্ট্রিকের তীব্র ব্যথায় তাৎক্ষণিক কি করা উচিত?"
        ),
        systemPromptExtension = "You are operating as B.I.O.-M.E.D.I.C. — personal bio-health intelligence. Provide sound, helpful wellness guidance, first-aid tips, and healthy lifestyle awareness in clear Bengali with standard medical disclaimers."
    )

    val NOTIFICATION_AGENT = AiAgent(
        id = "notification_agent",
        name = "N.O.T.I.F.I.E.R. Intel",
        titleBengali = "নোটিফিকেশন ইন্টেলিজেন্স এজেন্ট",
        roleBengali = "নোটিফিকেশন ঘোষণা, বিশ্লেষণ ও স্মার্ট রিপ্লাই",
        icon = Icons.Default.NotificationsActive,
        color = Color(0xFF38BDF8),
        description = "ইনকামিং মেসেজ পড়ে শোনানো, প্রায়োরিটি ফিল্টারিং, স্মার্ট রিপ্লাই সাজেশন এবং ব্যাকগ্রাউন্ড নোটিফিকেশন সামারি।",
        samplePrompts = listOf(
            "হোয়াটসঅ্যাপের নতুন মেসেজগুলো পড়ে শোনাও",
            "শেষ মেসেজটির উত্তর কী দেওয়া যায়?",
            "আজকের গুরুত্বপূর্ণ নোটিফিকেশনগুলো সামারি করো"
        ),
        systemPromptExtension = "You are N.O.T.I.F.I.E.R. — dedicated notification intelligence. Analyze incoming alerts, summarize messages, protect OTPs, and generate contextual replies."
    )

    val COMMUNICATION_AGENT = AiAgent(
        id = "communication_agent",
        name = "C.O.M.M.S. Telecom",
        titleBengali = "কমিউনিকেশন ও কলিং এজেন্ট",
        roleBengali = "টেলিফোন কল, এসএমএস ও কন্টাক্টস কন্ট্রোল",
        icon = Icons.Default.Call,
        color = Color(0xFF4ADE80),
        description = "ইনকামিং কলার ঘোষণা, ভয়েস আনসার/রিজেক্ট, সরাসরি ডায়াল ও কন্টাক্ট অ্যালিয়াস ম্যানেজমেন্ট।",
        samplePrompts = listOf(
            "কে কল করছে বলো",
            "কল রিসিভ করো",
            "বাবাকে কল দাও",
            "একটি নতুন কন্টাক্ট সেভ করো"
        ),
        systemPromptExtension = "You are C.O.M.M.S. — personal telecommunications agent. Manage calls, caller identification, contact aliases, and messaging."
    )

    val AUTOMATION_AGENT = AiAgent(
        id = "automation_agent",
        name = "A.U.T.O.M.A.T.O.R. Workflows",
        titleBengali = "অটোমেশন ও স্ক্রিন ইন্টেলিজেন্স",
        roleBengali = "স্ক্রিন একশন, ক্লিক ও রুটিন এক্সিকিউটর",
        icon = Icons.Default.TouchApp,
        color = Color(0xFFA855F7),
        description = "অ্যাক্সেসিবিলিটি দিয়ে অ্যাপে ক্লিক, স্ক্রল, ফর্ম পূরণ এবং মাল্টি-স্টেপ ওয়ার্কফ্লো এক্সিকিউশন।",
        samplePrompts = listOf(
            "স্ক্রিনের নিচে স্ক্রল করো",
            "প্রথম বাটনে ক্লিক করো",
            "সার্চ বক্সে টেক্সট লিখো"
        ),
        systemPromptExtension = "You are A.U.T.O.M.A.T.O.R. — screen action and workflow automation agent. Locate UI elements accurately and verify all actions."
    )

    val MEMORY_AGENT = AiAgent(
        id = "memory_agent",
        name = "M.E.M.O.R.Y. Core",
        titleBengali = "লং-টার্ম মেমোরি এজেন্ট",
        roleBengali = "ব্যক্তিগত প্রেফারেন্স ও নলেজ গ্রাফ",
        icon = Icons.Default.Psychology,
        color = Color(0xFFEC4899),
        description = "ব্যবহারকারীর পছন্দ, গুরুত্বপূর্ণ ঘটনা, কাজের নিয়ম ও নলেজ গ্রাফ সংরক্ষণ এবং পুনরুদ্ধার।",
        samplePrompts = listOf(
            "মনে রাখো আমার অফিসের সময় সকাল ৯টা",
            "আমার কোন কোন প্রেফারেন্স সেভ করা আছে?",
            "আমার প্রিয় জিনিসগুলোর তালিকা দাও"
        ),
        systemPromptExtension = "You are M.E.M.O.R.Y. Core — neural memory agent. Safely index personal facts, rules, and preferences for lifelong continuity."
    )

    val SECURITY_AGENT = AiAgent(
        id = "security_agent",
        name = "S.E.C.U.R.I.T.Y. Sentinel",
        titleBengali = "সিকিউরিটি ও প্রাইভেসি সেন্টিনেল",
        roleBengali = "পারমিশন মনিটরিং ও হিউম্যান গেট যাচাই",
        icon = Icons.Default.AdminPanelSettings,
        color = Color(0xFFFB923C),
        description = "সংবেদনশীল অ্যাকশন রোধ, ওটিপি ও ব্যাংক ডাটা সুরক্ষা, পারমিশন ট্র্যাকিং এবং এমার্জেন্সি স্টপ।",
        samplePrompts = listOf(
            "ফোনের সব সিকিউরিটি পারমিশন চেক করো",
            "সব কাজ বন্ধ করো",
            "প্রাইভেসি ড্যাশবোর্ড ওপেন করো"
        ),
        systemPromptExtension = "You are S.E.C.U.R.I.T.Y. Sentinel — privacy protector. Enforce human gates on high-risk actions, protect credentials, and ensure zero unverified side effects."
    )

    val TASK_AGENT = AiAgent(
        id = "task_agent",
        name = "T.A.S.K. Master",
        titleBengali = "রুটিন ও শিডিউল মাস্টার",
        roleBengali = "মর্নিং ব্রিফিং, নাইট রুটিন ও অ্যালার্মস",
        icon = Icons.Default.Alarm,
        color = Color(0xFF6366F1),
        description = "দৈনিক মর্নিং ব্রিফিং, নাইট রিভিউ, নির্দিষ্ট সময়ের ব্যক্তিগত অ্যালার্ম এবং রুটিন পরিচালনা।",
        samplePrompts = listOf(
            "মর্নিং রুটিন চালু করো",
            "আজকের শিডিউল ব্রিফিং দাও",
            "এক ঘণ্টা পর ব্যক্তিগত মিটিং অ্যালার্ম সেট করো"
        ),
        systemPromptExtension = "You are T.A.S.K. Master — routine and reminder orchestrator. Schedule exact alarms, execute morning/night routines, and keep the user punctual."
    )

    val HOME_DEVELOPER = AiAgent(
        id = "home_developer",
        name = "H.O.M.E. Developer Core",
        titleBengali = "হোম ডেভেলপার এজেন্ট",
        roleBengali = "অটোনোমাস স্কিল ফ্যাক্টরি, কোড ও সেল্ফ-রিপেয়ার আর্কিটেক্ট",
        icon = Icons.Default.Code,
        color = Color(0xFF00F5D4),
        description = "ইউটিউব, গিটহাব, টেক্সট, ভয়েস, ডকুমেন্ট ও স্ক্রিনশট থেকে নিরাপদ স্কিল তৈরি, সক্ষমতার ঘাটতি শনাক্ত, বহুভাষিক কোড প্যাচ ও ওয়েব অ্যাপ ওয়ার্কস্পেস নির্মাণ এবং চেকপয়েন্ট-সহ স্টপ/রিজিউম।",
        samplePrompts = listOf(
            "HOME DEVELOPER এখন কী করছ এবং কাজ কতদূর?",
            "সেল্ফ রিপেয়ার ও সক্ষমতা অডিট চালাও",
            "এই গিটহাব প্রোজেক্ট বিশ্লেষণ করে ইন্টিগ্রেশন প্রস্তাব বানাও",
            "একটি বাংলা টাস্ক ম্যানেজার ওয়েব অ্যাপ ওয়ার্কস্পেস তৈরি করো"
        ),
        systemPromptExtension = "You are HOME DEVELOPER Agent inside NAHID AI OS. Follow strict platform truth: report 0% progress when idle, block unsafe dynamic code loading or hardcoded secrets, mark native code patches as NOT_COMPILED_REQUIRES_BUILD, and support cooperative pause/resume checkpoints."
    )

    val INTELLIGENT_AGENT = AiAgent(
        id = "intelligent_agent",
        name = "I.N.T.E.L.L.I.G.E.N.T. Knowledge Core",
        titleBengali = "ইন্টেলিজেন্ট নলেজ ও ডিপ রিসার্চ এজেন্ট",
        roleBengali = "ফাস্ট-ফার্স্ট জ্ঞান পুনরুদ্ধার, সরকারি-সায়েন্টিফিক ডাটা ও ক্রস-ভেরিফায়েড গবেষণা",
        icon = Icons.Default.TravelExplore,
        color = Color(0xFF22D3EE),
        description = "JARVIS_MASTER-এর অধীনে L1/L2/L3/L4 ফাস্ট-ফার্স্ট নলেজ রাউটিং, বহু-উৎস প্যারালাল রিসার্চ, ক্লেইম-লেভেল প্রমাণ যাচাই এবং প্রমাণ-ভিত্তিক বাংলা উত্তর সংশ্লেষণ।",
        samplePrompts = listOf(
            "বাংলাদেশের সর্বশেষ অর্থনৈতিক ও সরকারি নীতিমালা যাচাই করে বলো",
            "আজকের বিশ্বের গুরুত্বপূর্ণ তাজা খবর ও প্রযুক্তি আপডেট দাও",
            "এই বিষয়টি একাধিক নির্ভরযোগ্য ওয়েব সূত্র থেকে ক্রস-চেক করো",
            "ডেঙ্গু বা স্বাস্থ্য সংক্রান্ত অফিসিয়াল গাইডলাইন কী বলছে?"
        ),
        systemPromptExtension = "You are I.N.T.E.L.L.I.G.E.N.T. — dedicated Knowledge Retrieval and Deep Research specialist operating under JARVIS_MASTER. Follow fast-first retrieval (L1 -> L2 -> L3 -> L4 bounded parallel search), attach canonical URLs, report claim-level verification status honestly (VERIFIED only with >=2 independent corroborated domains; otherwise SINGLE_SOURCE_ONLY / CONFLICTING / UNVERIFIED), and never fabricate facts or citations."
    )

    val ALL_AGENTS = listOf(
        JARVIS_MASTER,
        FRIDAY_AGENT,
        EDITH_AGENT,
        HOMER_AGENT,
        VISION_AGENT,
        BIO_MEDIC_AGENT,
        NOTIFICATION_AGENT,
        COMMUNICATION_AGENT,
        AUTOMATION_AGENT,
        MEMORY_AGENT,
        SECURITY_AGENT,
        TASK_AGENT,
        HOME_DEVELOPER,
        INTELLIGENT_AGENT
    )

    fun getAgentById(id: String?): AiAgent {
        if (id.isNullOrBlank()) return JARVIS_MASTER
        return ALL_AGENTS.find { it.id.equals(id, ignoreCase = true) } ?: JARVIS_MASTER
    }
}
