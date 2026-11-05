package hu.hanizso.raktar

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import hu.hanizso.raktar.data.Store
import hu.hanizso.raktar.data.SyncWorker

class RaktarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.get(this)
        // Amikor az app háttérbe kerül, a várakozó mozgások elküldése (biztonsági háló)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                val s = Store.get(this@RaktarApp)
                if (s.isConfigured && s.state.value.pendingCount > 0) SyncWorker.enqueue(this@RaktarApp)
            }
        })
    }
}
