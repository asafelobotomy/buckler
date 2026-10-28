package dev.solon.bucklerhome

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice

/** A button's place on a model's drawing: dp from the body's top-left; [more] for a second key (volume). */
data class Placed(val button: RemoteButton, val x: Int, val y: Int, val more: List<Pair<Int, Int>> = emptyList())

/**
 * The SHIELD remotes, told apart by NVIDIA's USB/Bluetooth product id (the same ids name its key
 * layout files on the TV), plus "other" for TV and third-party remotes, which get a plain list.
 * [triangular] is the 2019 remote, whose back comes to a corner edge; the others are flat.
 */
enum class RemoteModel(
    val label: String,
    val short: String,
    val productId: Int?,
    val triangular: Boolean,
    val bodyWidth: Int,
    val bodyHeight: Int,
    val layout: List<Placed>,
) {
    SHIELD_2019(
        "SHIELD remote (2019, triangular)", "2019 (triangular)", 0x7217, true, 120, 360,
        listOf(
            // As on the remote: Power and ≡ on top, the D-pad, then two columns of four, Netflix last.
            Placed(RemoteButton.POWER, 38, 28),
            Placed(RemoteButton.MENU, 82, 28),
            Placed(RemoteButton.DPAD, 60, 92),
            Placed(RemoteButton.BACK, 38, 160),
            Placed(RemoteButton.HOME, 82, 160),
            Placed(RemoteButton.FAST_FORWARD, 38, 200),
            Placed(RemoteButton.ASSIST, 82, 200),
            Placed(RemoteButton.PLAY_PAUSE, 38, 240),
            Placed(RemoteButton.VOLUME, 82, 240, more = listOf(82 to 280)),
            Placed(RemoteButton.REWIND, 38, 280),
            Placed(RemoteButton.NETFLIX, 60, 326),
        ),
    ),
    SHIELD_2017(
        "SHIELD remote (2017, flat)", "2017 (flat)", 0x7213, false, 96, 330,
        listOf(
            Placed(RemoteButton.VOICE, 48, 40),
            Placed(RemoteButton.DPAD, 48, 112),
            Placed(RemoteButton.BACK, 24, 184),
            Placed(RemoteButton.HOME, 72, 184),
            Placed(RemoteButton.PLAY_PAUSE, 48, 230),
            Placed(RemoteButton.VOLUME, 48, 280),
        ),
    ),
    SHIELD_2015(
        "SHIELD remote (2015, flat with a volume slider)", "2015 (volume slider)", 0x7212, false, 96, 330,
        listOf(
            Placed(RemoteButton.VOICE, 48, 40),
            Placed(RemoteButton.DPAD, 48, 112),
            Placed(RemoteButton.BACK, 24, 184),
            Placed(RemoteButton.HOME, 72, 184),
            Placed(RemoteButton.VOLUME, 48, 260),
        ),
    ),
    OTHER(
        "Another remote (TV or third-party)", "Other remote", null, false, 0, 0,
        RemoteButton.entries.filter { it.mapping == RemoteButton.Mapping.BUCKLER && it != RemoteButton.NETFLIX }
            .map { Placed(it, 0, 0) },
    ),
    ;

    val drawn: Boolean get() = bodyHeight > 0

    companion object {
        const val NVIDIA_VENDOR = 0x0955
        fun forProduct(vendor: Int, product: Int): RemoteModel? =
            if (vendor != NVIDIA_VENDOR) null else entries.firstOrNull { it.productId == product }
    }
}

/** Which remote is in use: found from the connected input devices, or chosen in Settings. */
object RemoteModels {
    private const val PREFS = "bucklerhome_remote"
    private const val KEY_CHOICE = "model"
    private const val KEY_LAST_SEEN = "model_seen"

    /** What a connected input device says it is; null when none is a known SHIELD remote. */
    fun detect(context: Context): RemoteModel? {
        val input = context.getSystemService(InputManager::class.java) ?: return null
        return input.inputDeviceIds.asSequence()
            .mapNotNull { id -> input.getInputDevice(id) }
            .filter { !it.isVirtual && it.supportsSource(InputDevice.SOURCE_KEYBOARD) }
            .mapNotNull { RemoteModel.forProduct(it.vendorId, it.productId) }
            .firstOrNull()
            ?.also { seen -> prefs(context).edit().putString(KEY_LAST_SEEN, seen.name).apply() }
    }

    /** Your choice, else what is connected, else what was connected last; null if never known. */
    fun current(context: Context): RemoteModel? = chosen(context) ?: detect(context) ?: lastSeen(context)

    /** null means "find it automatically". */
    fun chosen(context: Context): RemoteModel? = named(prefs(context).getString(KEY_CHOICE, null))

    fun choose(context: Context, model: RemoteModel?) {
        val edit = prefs(context).edit()
        if (model == null) edit.remove(KEY_CHOICE) else edit.putString(KEY_CHOICE, model.name)
        edit.apply()
    }

    private fun lastSeen(context: Context): RemoteModel? = named(prefs(context).getString(KEY_LAST_SEEN, null))

    private fun named(name: String?): RemoteModel? = RemoteModel.entries.firstOrNull { it.name == name }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
