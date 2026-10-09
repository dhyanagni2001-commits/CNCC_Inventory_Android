package com.cnanjappa.inventory

import android.app.Application
import android.content.Context
import com.cnanjappa.inventory.data.AppDatabase
import com.cnanjappa.inventory.data.InventoryRepository
import com.cnanjappa.inventory.export.AutoBackup
import com.cnanjappa.inventory.export.Backup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class InventoryApp : Application() {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
        AutoBackup.schedule(this)
    }
}

enum class LaunchState { OPENING, READY, FAILED }

/** Process-wide objects. The database opens on a background thread as soon as the process starts. */
class Container(val appContext: Context) {
    private val context get() = appContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile var db: AppDatabase = AppDatabase.build(context)
        private set
    @Volatile var repo: InventoryRepository = InventoryRepository(db)
        private set

    private val _launch = MutableStateFlow(LaunchState.OPENING)
    val launch: StateFlow<LaunchState> = _launch

    /** Branding is shown once per process (cold launch), never on resume or rotation. */
    var brandingShown = false

    init { warmUp() }

    fun warmUp() {
        _launch.value = LaunchState.OPENING
        scope.launch {
            _launch.value = try {
                repo.catalog()
                repo.dao.variantCount()
                LaunchState.READY
            } catch (e: Exception) {
                android.util.Log.e("Inventory", "Database open failed", e)
                LaunchState.FAILED
            }
        }
    }

    fun backup() = Backup(context, db)

    /** Closes the database, swaps in a validated restore, and reopens. */
    @Synchronized
    fun replaceDatabase(restored: File) {
        db.close()
        try {
            Backup.swapIn(context, restored)
        } finally {
            db = AppDatabase.build(context)
            repo = InventoryRepository(db)
            warmUp()
        }
    }
}

val Context.container: Container get() = (applicationContext as InventoryApp).container
