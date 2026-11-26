package com.example.bruceir

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.util.zip.GZIPInputStream

/**
 * Wbudowana baza Flipper IRDB.
 *
 * Baza jest duża (~4 MB gz, kilkadziesiąt MB po dekompresji), dlatego:
 *  - jest wczytywana leniwie przy pierwszym otwarciu folderu i trzymana w pamięci
 *    tylko w obrębie procesu (nie trafia do bruce_db.json, żeby każdy zapis
 *    biblioteki nie kopiował dziesiątek MB),
 *  - potrafi wczytać zarówno `flipper_db.json.gz`, jak i `flipper_db.json`
 *    (starsze buildy miały tylko nieskompresowany plik),
 *  - błędy wczytywania dają akcję „spróbuj ponownie / wskaż plik”, a nie cichy
 *    pusty folder.
 */
object BruceFlipperCache {

    /** Nazwa folderu zarządzanego w bibliotece użytkownika. */
    const val FOLDER_NAME = "🐬 Flipper IRDB"

    /**
     * Nazwy pliku bazy w assets. AGP potrafi „rozpakować" asset kończący się na `.gz`
     * podczas budowania APK (i wtedy w APK jest tylko wielki JSON), dlatego podstawowa
     * nazwa nie ma rozszerzenia `.gz`; pozostałe wpisy to fallbacki dla starszych buildów.
     */
    private val ASSET_CANDIDATES = listOf(
        "flipper_irdb.bin",
        "flipper_db.json.gz",
        "flipper_db.json"
    )

    private val loadMutex = Mutex()

    @Volatile
    private var cachedFolder: IrFolder? = null

    /** Czy nazwa folderu należy do bazy zarządzanej przez aplikację (nie do użytkownika). */
    fun isManagedFolderName(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.startsWith("🐬 Flipper IRDB") ||
            trimmed.startsWith("FLIPPER-IRDB-MAIN") ||
            trimmed.equals("Flipper-IRDB-main", ignoreCase = true)
    }

    /**
     * Znajduje (lub tworzy) folder bazy Flipper w podanym folderze-rodzicu.
     * Placeholder zawiera jedną komendę-informację, którą UI zamienia na treść bazy.
     */
    fun locateOrCreate(parent: IrFolder): IrFolder {
        val existing = parent.items.filterIsInstance<IrFolder>()
            .firstOrNull { isManagedFolderName(it.name) }
        if (existing != null) return existing
        val created = IrFolder(FOLDER_NAME).apply {
            iconName = "🐬"
            items.add(
                Command(
                    "📂 [Kliknij, aby wczytać wbudowaną bazę Flipper IRDB]",
                    38000,
                    intArrayOf(100, 100)
                )
            )
        }
        parent.items.add(0, created)
        return created
    }

    /** Czy folder jest tylko placeholderem (nagłówek-informacja zamiast danych). */
    fun isPlaceholder(folder: IrFolder): Boolean =
        folder.items.isEmpty() || (
            folder.items.size == 1 &&
                (folder.items[0] as? Command)?.let { isPlaceholderCommandName(it.name) } == true
            )

    fun isPlaceholderCommandName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("kliknij") &&
            (lower.contains("flipper") || lower.contains("wczytać") || lower.contains("wczytac") ||
                lower.contains("zaimportować") || lower.contains("zaimportowac"))
    }

    /** Czy komenda jest komunikatem błędu wczytywania bazy (pozwala pokazać „spróbuj ponownie”). */
    fun isErrorCommandName(name: String): Boolean =
        name.lowercase().let { it.contains("błąd") || it.contains("blad") || it.contains("niedostępna") }

    /** Ładuje bazę do podanego folderu. Zwraca liczbę elementów albo rzuca wyjątek z czytelnym opisem. */
    suspend fun loadInto(context: Context, folder: IrFolder): Int {
        val loaded = getOrLoad(context)
        val firstCommand = loaded.items.singleOrNull() as? Command
        if (firstCommand != null && isErrorCommandName(firstCommand.name)) {
            throw IllegalStateException(firstCommand.name)
        }
        return withContext(Dispatchers.Main) {
            folder.items.clear()
            folder.items.addAll(loaded.items)
            folder.name = FOLDER_NAME
            folder.iconName = "🐬"
            folder.items.size
        }
    }

    /**
     * Wczytuje bazę z assets. Rzuca IllegalStateException z konkretnym powodem,
     * gdy w APK brakuje plików bazy (np. zepsuty build).
     */
    suspend fun getOrLoad(context: Context): IrFolder = withContext(Dispatchers.IO) {
        cachedFolder?.let { return@withContext it }
        loadMutex.withLock {
            cachedFolder?.let { return@withContext it }
            val folder = try {
                parseAsset(context)
            } catch (e: Exception) {
                android.util.Log.e("BruceCache", "Error loading Flipper DB: ${e.message}", e)
                IrFolder(FOLDER_NAME).apply {
                    iconName = "🐬"
                    items.add(
                        Command(
                            "⚠ Błąd wczytywania bazy Flipper: ${e.message ?: "nieznany błąd"}. " +
                                "Kliknij, aby wskazać plik .json/.json.gz",
                            38000,
                            intArrayOf(100, 100)
                        )
                    )
                }
            }
            cachedFolder = folder
            folder
        }
    }

    fun clearCache() {
        cachedFolder = null
    }

    private fun parseAsset(context: Context): IrFolder {
        val stream = openDatabaseStream(context)
        val loaded = stream.use { BruceUtils.streamParseJson(it.bufferedReader()) }
        // Odpakuj sztuczny korzeń (FLIPPER-IRDB-main), żeby od razu było widać kategorie.
        val unwrapped = if (
            loaded.items.size == 1 &&
            loaded.items[0] is IrFolder &&
            (loaded.items[0] as IrFolder).name.contains("FLIPPER", true)
        ) {
            loaded.items[0] as IrFolder
        } else {
            loaded
        }
        unwrapped.name = FOLDER_NAME
        unwrapped.iconName = "🐬"
        return unwrapped
    }

    /** Otwiera bazę z assets: pierwszy istniejący kandydat, z auto-detekcją gzip. */
    private fun openDatabaseStream(context: Context): BufferedInputStream {
        val assetName = ASSET_CANDIDATES.firstOrNull { assetExists(context, it) }
            ?: throw IllegalStateException(
                "brak pliku bazy Flipper w APK (szukano: ${ASSET_CANDIDATES.joinToString()}). " +
                    "Przebuduj aplikację: gradlew clean assembleDebug"
            )
        val buffered = BufferedInputStream(context.assets.open(assetName))
        buffered.mark(2)
        val magic = buffered.read() or (buffered.read() shl 8)
        buffered.reset()
        return if (magic == 0x8b1f) {
            BufferedInputStream(GZIPInputStream(buffered))
        } else {
            buffered
        }
    }

    private fun assetExists(context: Context, name: String): Boolean = try {
        context.assets.open(name).use { true }
    } catch (_: Exception) {
        false
    }

    /** Wczytuje bazę z pliku wskazanego przez użytkownika (.json lub .json.gz). */
    suspend fun loadFromBytes(context: Context, bytes: ByteArray): IrFolder = withContext(Dispatchers.IO) {
        val buffered = BufferedInputStream(bytes.inputStream())
        buffered.mark(2)
        val magic = buffered.read() or (buffered.read() shl 8)
        buffered.reset()
        val stream = if (magic == 0x8b1f) {
            BufferedInputStream(GZIPInputStream(buffered))
        } else {
            buffered
        }
        val result = stream.use { BruceUtils.streamParseJson(it.bufferedReader()) }
        if (result.items.isEmpty()) {
            throw IllegalStateException("plik nie zawiera folderów ani komend IR")
        }
        result.let { loaded ->
            val unwrapped = if (
                loaded.items.size == 1 &&
                loaded.items[0] is IrFolder &&
                (loaded.items[0] as IrFolder).name.contains("FLIPPER", true)
            ) {
                loaded.items[0] as IrFolder
            } else {
                loaded
            }
            unwrapped.name = FOLDER_NAME
            unwrapped.iconName = "🐬"
            cachedFolder = unwrapped
            unwrapped
        }
    }
}
