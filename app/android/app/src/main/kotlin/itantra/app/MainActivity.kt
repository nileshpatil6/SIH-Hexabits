package itantra.app

import itantra.bridge.EventsApi
import itantra.bridge.EventsForwarder
import itantra.bridge.MeshApi
import itantra.bridge.MeshApiImpl
import itantra.bridge.ModelApi
import itantra.bridge.ModelApiImpl
import itantra.bridge.SpeechApi
import itantra.bridge.SpeechApiImpl
import itantra.core.ItantraCore
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        val core = ItantraCore.get(this)
        val events = EventsApi(messenger)
        core.events = EventsForwarder(events)
        SpeechApi.setUp(messenger, SpeechApiImpl(core))
        MeshApi.setUp(messenger, MeshApiImpl(this, core))
        ModelApi.setUp(messenger, ModelApiImpl(core, events))
    }

    override fun onDestroy() {
        ItantraCore.get(this).events = null
        super.onDestroy()
    }
}

