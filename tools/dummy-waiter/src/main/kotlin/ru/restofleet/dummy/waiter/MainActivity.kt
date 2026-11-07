package ru.restofleet.dummy.waiter

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Dummy waiter v${BuildConfig.VERSION_NAME}" })
    }
}
