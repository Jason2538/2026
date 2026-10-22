package com.bookscan.app

import android.app.Application

class ScanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("opencv_java4")
    }
}
