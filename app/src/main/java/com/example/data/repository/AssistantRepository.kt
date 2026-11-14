package com.example.data.repository

import com.example.data.local.*
import kotlinx.coroutines.flow.Flow

class AssistantRepository(private val dao: AssistantDao) {

    val allNotes: Flow<List<NoteEntity>> = dao.getAllNotes()
    val profile: Flow<JarvisProfileEntity?> = dao.getProfile()

    suspend fun insertOrUpdateNote(note: NoteEntity): Long {
        return if (note.id == 0L) {
            dao.insertNote(note)
        } else {
            dao.updateNote(note)
            note.id
        }
    }

    suspend fun deleteNote(id: Long) = dao.deleteNoteById(id)

    suspend fun updateProfile(profile: JarvisProfileEntity) {
        dao.insertProfile(profile)
    }

    suspend fun deleteProfile() {
        dao.clearProfile()
    }

    // Notifications
    val allActiveNotifications: Flow<List<AppNotificationEntity>> = dao.getActiveNotifications()
    val unreadNotificationCount: Flow<Int> = dao.getUnreadNotificationsCount()

    suspend fun insertNotification(notification: AppNotificationEntity): Long = dao.insertNotification(notification)
    suspend fun markNotificationAsRead(id: Long) = dao.markNotificationAsRead(id)
    suspend fun markAllNotificationsAsRead() = dao.markAllNotificationsAsRead()
    suspend fun deleteNotification(id: Long) = dao.deleteNotificationById(id)
    suspend fun clearAllNotifications() = dao.clearAllNotifications()
    suspend fun getAllExistingEventKeys(): List<String> = dao.getAllExistingEventKeys()
}
