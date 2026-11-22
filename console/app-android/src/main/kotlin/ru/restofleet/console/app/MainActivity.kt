package ru.restofleet.console.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.arkivanov.decompose.retainedComponent
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.android.getKoin
import ru.restofleet.console.root.RootContent
import ru.restofleet.console.root.di.createRootComponent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = retainedComponent { context -> getKoin().createRootComponent(context, Dispatchers.Main) }
        setContent { RootContent(root) }
    }
}
