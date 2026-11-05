package com.paper2binary.app

import android.app.Application
import com.paper2binary.app.data.AppDatabase
import com.paper2binary.app.data.SurveyRepository

/** Holds the app-wide objects; the app is small enough not to need a DI framework. */
class P2BApplication : Application() {
    val repository: SurveyRepository by lazy {
        SurveyRepository(this, AppDatabase.create(this))
    }
}
