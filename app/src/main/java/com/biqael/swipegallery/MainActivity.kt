package com.biqael.swipegallery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.*

class MainActivity : Activity() {
    private val ids = ArrayList<Long>()
    private val kept = HashSet<Long>()
    private val pending = HashSet<Long>()
    private val trash = ArrayList<Uri>()
    private val history = ArrayList<Int>()
    private var idx = 0
    private var busy = false
    private var token = 0

    private lateinit var root: FrameLayout
    private lateinit var card: ImageView
    private lateinit var counter: TextView
    private lateinit var tagKeep: TextView
    private lateinit var tagDel: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun uriOf(id: Long) =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        buildUi()
        val p = getSharedPreferences("p", MODE_PRIVATE)
        p.getStringSet("kept", emptySet())!!
            .forEach { it.toLongOrNull()?.let { id -> kept.add(id) } }
        p.getStringSet("trash", emptySet())!!
            .forEach { it.toLongOrNull()?.let { id -> pending.add(id) } }
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)
            loadPhotos()
        else requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), 1)
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#121212")) }
        root.setOnApplyWindowInsetsListener { v, i ->
            val b = i.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom); i
        }
        setContentView(root)

        counter = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            setOnClickListener { if (trash.isNotEmpty() && !busy) deleteTrash() }
            setOnLongClickListener {
                kept.clear(); saveSets()
                Toast.makeText(context, "Riwayat 'simpan' direset", Toast.LENGTH_SHORT).show()
                loadPhotos(); true
            }
        }
        root.addView(counter, FrameLayout.LayoutParams(-1, dp(48), Gravity.TOP))

        card = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }
        root.addView(card, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(dp(16), dp(52), dp(16), dp(120))
        })
        tagKeep = tag("SIMPAN", "#1FA971", Gravity.TOP or Gravity.START)
        tagDel = tag("HAPUS", "#E5484D", Gravity.TOP or Gravity.END)

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER }
        bar.addView(btn("✕", "#E5484D") { swipeBtn(-1) })
        bar.addView(btn("↶", "#999999") { undo() })
        bar.addView(btn("♥", "#1FA971") { swipeBtn(1) })
        root.addView(bar, FrameLayout.LayoutParams(-1, dp(100), Gravity.BOTTOM))

        card.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                if (busy) return true
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> sx = e.rawX
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - sx
                        v.translationX = dx; v.rotation = dx / 40f
                        tagKeep.alpha = (dx / dp(120)).coerceIn(0f, 1f)
                        tagDel.alpha = (-dx / dp(120)).coerceIn(0f, 1f)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dx = e.rawX - sx
                        if (dx > dp(100)) fling(1) else if (dx < -dp(100)) fling(-1) else resetCard()
                    }
                }
                return true
            }
        })
    }

    private fun tag(t: String, c: String, g: Int) = TextView(this).apply {
        text = t; textSize = 22f; setTextColor(Color.parseColor(c)); alpha = 0f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        root.addView(this, FrameLayout.LayoutParams(-2, -2, g).apply {
            setMargins(dp(36), dp(76), dp(36), 0)
        })
    }

    private fun btn(t: String, c: String, f: () -> Unit) = Button(this).apply {
        text = t; textSize = 24f; setTextColor(Color.parseColor(c))
        layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply { setMargins(dp(10), 0, dp(10), 0) }
        setOnClickListener { f() }
    }

    private fun resetCard() {
        card.animate().cancel()
        card.translationX = 0f; card.rotation = 0f
        tagKeep.alpha = 0f; tagDel.alpha = 0f
    }

    private fun swipeBtn(dir: Int) {
        if (busy || idx >= ids.size) return
        (if (dir > 0) tagKeep else tagDel).alpha = 1f
        fling(dir)
    }

    private fun fling(dir: Int) {
        if (busy || idx >= ids.size) return
        busy = true
        card.animate().translationX(dir * root.width * 1.2f).rotation(dir * 25f)
            .setDuration(220).withEndAction { decide(dir) }.start()
    }

    private fun decide(dir: Int) {
        val id = ids[idx]
        if (dir < 0) { trash.add(uriOf(id)); pending.add(id) } else kept.add(id)
        saveSets()
        history.add(dir); idx++
        resetCard(); busy = false
        show()
        if (trash.size >= 20 && idx < ids.size) deleteTrash()
    }

    private fun undo() {
        if (busy || history.isEmpty()) return
        val dir = history.removeAt(history.lastIndex); idx--
        val id = ids[idx]
        if (dir < 0) { trash.remove(uriOf(id)); pending.remove(id) } else kept.remove(id)
        saveSets()
        show()
    }

    private fun saveSets() {
        getSharedPreferences("p", MODE_PRIVATE).edit()
            .putStringSet("kept", kept.map { it.toString() }.toSet())
            .putStringSet("trash", pending.map { it.toString() }.toSet()).apply()
    }

    private fun loadPhotos() {
        ids.clear(); trash.clear(); history.clear(); idx = 0
        val exist = HashSet<Long>()
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), null, null,
            MediaStore.Images.Media.DATE_ADDED + " DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id in kept) continue
                if (id in pending) { trash.add(uriOf(id)); exist.add(id) } else ids.add(id)
            }
        }
        pending.retainAll(exist)
        saveSets()
        show()
    }

    private fun show() {
        stats()
        if (idx >= ids.size) {
            card.setImageDrawable(null)
            if (ids.isNotEmpty() || trash.isNotEmpty()) showSummary()
            else counter.text = "Tidak ada foto baru"
            return
        }
        val t = ++token
        val uri = uriOf(ids[idx])
        card.setImageDrawable(null)
        Thread {
            val bmp = try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                    val s = maxOf(info.size.width, info.size.height) / 1600
                    if (s > 1) d.setTargetSampleSize(s)
                }
            } catch (e: Exception) { null }
            runOnUiThread { if (t == token && bmp != null) card.setImageBitmap(bmp) }
        }.start()
    }

    private fun stats() {
        counter.text = "${minOf(idx + 1, ids.size)} / ${ids.size}   ✕ ${trash.size}   ♥ ${history.count { it > 0 }}"
    }

    private fun showSummary() {
        val b = AlertDialog.Builder(this).setCancelable(false)
            .setTitle("Selesai")
            .setMessage("Disimpan: ${history.count { it > 0 }}\nDibuang: ${trash.size}")
            .setNeutralButton("Urungkan terakhir") { _, _ -> undo() }
            .setNegativeButton("Tutup", null)
        if (trash.isNotEmpty()) b.setPositiveButton("Hapus ${trash.size} foto") { _, _ -> deleteTrash() }
        b.show()
    }

    private fun deleteTrash() {
        if (trash.isEmpty()) return
        val pi = MediaStore.createDeleteRequest(contentResolver, trash)
        startIntentSenderForResult(pi.intentSender, 42, null, 0, 0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 42) {
            if (res == RESULT_OK) Toast.makeText(this, "Foto terhapus", Toast.LENGTH_SHORT).show()
            loadPhotos()
        }
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) loadPhotos()
        else counter.text = "Izin galeri diperlukan"
    }
}
