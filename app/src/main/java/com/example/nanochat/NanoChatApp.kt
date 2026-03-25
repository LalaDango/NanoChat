package com.example.nanochat

import android.app.Application
import com.example.nanochat.data.local.AppDatabase
import com.example.nanochat.data.repository.NanoChatRepository
import com.example.nanochat.data.repository.PresetRepository
import com.example.nanochat.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NanoChatApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var chatRepository: NanoChatRepository
        private set

    lateinit var presetRepository: PresetRepository
        private set

    override fun onCreate() {
        super.onCreate()

        database = AppDatabase.getInstance(this)
        settingsRepository = SettingsRepository(this)
        chatRepository = NanoChatRepository(
            context = applicationContext,
            database = database,
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            settingsRepository = settingsRepository
        )

        presetRepository = PresetRepository(database.presetDao())

        // Pre-load Nano model into memory for faster first inference
        CoroutineScope(Dispatchers.IO).launch {
            chatRepository.warmup()
        }
    }
}
