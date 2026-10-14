package com.fajar.pdfeditor

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.fajar.pdfeditor.databinding.ActivityMainBinding
import com.fajar.pdfeditor.model.ToolMode
import com.fajar.pdfeditor.pdf.PdfEngine
import com.fajar.pdfeditor.ui.SignaturePadView
import com.fajar.pdfeditor.utils.ImageUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pdfEngine: PdfEngine

    private var currentActivePageIndex = 0
    private var openedFileName: String = "dokumen.pdf"

    // Dialog state for image import
    private var selectedRawImageBitmap: Bitmap? = null
    private var processedSignatureBitmap: Bitmap? = null
    private var importPreviewImageView: ImageView? = null
    private var noImageHintView: TextView? = null

    // File Pickers
    private val openPdfLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { loadPdfDocument(it) }
    }

    private val savePdfLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        uri?.let { exportPdfToUri(it) }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processImportedImageUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pdfEngine = PdfEngine(applicationContext)

        setupUI()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        val action = intent.action
        val data = intent.data

        if (Intent.ACTION_VIEW == action && data != null) {
            loadPdfDocument(data)
        } else if (Intent.ACTION_SEND == action) {
            val clipUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            clipUri?.let { loadPdfDocument(it) }
        }
    }

    private fun setupUI() {
        // Open PDF Buttons
        binding.btnOpenPdf.setOnClickListener { openPdfLauncher.launch(arrayOf("application/pdf")) }
        binding.btnSelectFileEmpty.setOnClickListener { openPdfLauncher.launch(arrayOf("application/pdf")) }

        // Navigation
        binding.btnPrevPage.setOnClickListener { navigatePage(-1) }
        binding.btnNextPage.setOnClickListener { navigatePage(1) }

        // Top Actions
        binding.btnUndo.setOnClickListener { binding.pdfCanvasView.undoLastAnnotation() }
        binding.btnPageTools.setOnClickListener { showPageToolsDialog() }
        binding.btnSave.setOnClickListener {
            val defaultName = "edited_${openedFileName.removeSuffix(".pdf")}.pdf"
            savePdfLauncher.launch(defaultName)
        }
        binding.btnShare.setOnClickListener { shareEditedPdf() }

        // Bottom Tools
        binding.btnToolSelect.setOnClickListener { selectTool(ToolMode.SELECT) }
        binding.btnToolSignature.setOnClickListener { showSignatureDialog() }
        binding.btnToolText.setOnClickListener { showInsertTextDialog() }
        binding.btnToolWhiteout.setOnClickListener { selectTool(ToolMode.WHITEOUT) }
        binding.btnToolPen.setOnClickListener { selectTool(ToolMode.PEN) }
        binding.btnToolHighlight.setOnClickListener { selectTool(ToolMode.HIGHLIGHT) }
        binding.btnClearPage.setOnClickListener { binding.pdfCanvasView.clearAllAnnotations() }

        updateToolSelectionUI(ToolMode.SELECT)
    }

    private fun selectTool(mode: ToolMode) {
        binding.pdfCanvasView.currentTool = mode
        updateToolSelectionUI(mode)
    }

    private fun updateToolSelectionUI(activeMode: ToolMode) {
        binding.btnToolSelect.isSelected = activeMode == ToolMode.SELECT
        binding.btnToolWhiteout.isSelected = activeMode == ToolMode.WHITEOUT
        binding.btnToolPen.isSelected = activeMode == ToolMode.PEN
        binding.btnToolHighlight.isSelected = activeMode == ToolMode.HIGHLIGHT
    }

    private fun loadPdfDocument(uri: Uri) {
        openedFileName = queryFileName(uri) ?: "dokumen.pdf"
        binding.tvDocTitle.text = openedFileName

        binding.loadingLayout.visibility = View.VISIBLE
        binding.tvLoadingMsg.text = "Membuka dokumen PDF…"

        lifecycleScope.launch {
            val success = pdfEngine.open(uri)
            binding.loadingLayout.visibility = View.GONE

            if (success && pdfEngine.totalPages > 0) {
                currentActivePageIndex = 0
                binding.emptyView.visibility = View.GONE
                binding.pdfScrollView.visibility = View.VISIBLE
                binding.pageNavLayout.visibility = View.VISIBLE
                binding.toolbarScrollView.visibility = View.VISIBLE
                binding.btnUndo.visibility = View.VISIBLE
                binding.btnPageTools.visibility = View.VISIBLE
                binding.btnSave.visibility = View.VISIBLE
                binding.btnShare.visibility = View.VISIBLE

                renderCurrentPage()
            } else {
                Toast.makeText(this@MainActivity, "Gagal membuka file PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderCurrentPage() {
        val nonDeletedStates = pdfEngine.pageStates.filter { !it.isDeleted }
        if (nonDeletedStates.isEmpty()) {
            Toast.makeText(this, "Semua halaman telah dihapus", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentActivePageIndex >= nonDeletedStates.size) {
            currentActivePageIndex = nonDeletedStates.size - 1
        } else if (currentActivePageIndex < 0) {
            currentActivePageIndex = 0
        }

        val state = nonDeletedStates[currentActivePageIndex]
        binding.tvPageIndicator.text = "${currentActivePageIndex + 1} / ${nonDeletedStates.size}"

        lifecycleScope.launch {
            binding.loadingLayout.visibility = View.VISIBLE
            binding.tvLoadingMsg.text = "Memuat halaman ${currentActivePageIndex + 1}…"

            val targetWidth = resources.displayMetrics.widthPixels - 32
            val bmp = pdfEngine.renderPageBitmap(state.originalPageIndex, targetWidth)
            binding.loadingLayout.visibility = View.GONE

            if (bmp != null) {
                binding.pdfCanvasView.setPage(bmp, state)
            }
        }
    }

    private fun navigatePage(delta: Int) {
        val nonDeletedCount = pdfEngine.pageStates.count { !it.isDeleted }
        val newIndex = currentActivePageIndex + delta
        if (newIndex in 0 until nonDeletedCount) {
            currentActivePageIndex = newIndex
            renderCurrentPage()
        }
    }

    // --- DIALOG: Signature (Direct Pad + Image Import with Auto-Transparent White Remover) ---
    private fun showSignatureDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_signature)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val btnTabDraw = dialog.findViewById<MaterialButton>(R.id.btnTabDraw)
        val btnTabImport = dialog.findViewById<MaterialButton>(R.id.btnTabImport)
        val layoutTabDraw = dialog.findViewById<LinearLayout>(R.id.layoutTabDraw)
        val layoutTabImport = dialog.findViewById<LinearLayout>(R.id.layoutTabImport)

        val signaturePad = dialog.findViewById<SignaturePadView>(R.id.signaturePad)
        val btnClearPad = dialog.findViewById<MaterialButton>(R.id.btnClearPad)
        val colorInkBlack = dialog.findViewById<View>(R.id.colorInkBlack)
        val colorInkBlue = dialog.findViewById<View>(R.id.colorInkBlue)

        val btnPickImage = dialog.findViewById<MaterialButton>(R.id.btnPickImage)
        val ivPreview = dialog.findViewById<ImageView>(R.id.ivImportPreview)
        val tvNoImageHint = dialog.findViewById<TextView>(R.id.tvNoImageHint)
        val cbRemoveWhiteBg = dialog.findViewById<MaterialCheckBox>(R.id.cbRemoveWhiteBg)
        val sliderThreshold = dialog.findViewById<Slider>(R.id.sliderThreshold)
        val tvThresholdVal = dialog.findViewById<TextView>(R.id.tvThresholdVal)

        val btnCancel = dialog.findViewById<MaterialButton>(R.id.btnCancelSignature)
        val btnApply = dialog.findViewById<MaterialButton>(R.id.btnApplySignature)

        importPreviewImageView = ivPreview
        noImageHintView = tvNoImageHint
        selectedRawImageBitmap = null
        processedSignatureBitmap = null

        var activeTab = 0 // 0: Draw, 1: Import

        fun updateTabs() {
            if (activeTab == 0) {
                btnTabDraw.setBackgroundColor(getColor(R.color.accent_blue))
                btnTabDraw.setTextColor(getColor(R.color.on_primary))
                btnTabImport.setBackgroundColor(Color.TRANSPARENT)
                btnTabImport.setTextColor(getColor(R.color.text_primary))
                layoutTabDraw.visibility = View.VISIBLE
                layoutTabImport.visibility = View.GONE
            } else {
                btnTabImport.setBackgroundColor(getColor(R.color.accent_blue))
                btnTabImport.setTextColor(getColor(R.color.on_primary))
                btnTabDraw.setBackgroundColor(Color.TRANSPARENT)
                btnTabDraw.setTextColor(getColor(R.color.text_primary))
                layoutTabDraw.visibility = View.GONE
                layoutTabImport.visibility = View.VISIBLE
            }
        }

        btnTabDraw.setOnClickListener { activeTab = 0; updateTabs() }
        btnTabImport.setOnClickListener { activeTab = 1; updateTabs() }

        // Draw tab actions
        colorInkBlack.setOnClickListener { signaturePad.setStrokeColor(Color.parseColor("#0F172A")) }
        colorInkBlue.setOnClickListener { signaturePad.setStrokeColor(Color.parseColor("#1E40AF")) }
        btnClearPad.setOnClickListener { signaturePad.clear() }

        // Import tab actions
        btnPickImage.setOnClickListener { pickImageLauncher.launch("image/*") }

        fun updateProcessedImage() {
            val raw = selectedRawImageBitmap ?: return
            val removeBg = cbRemoveWhiteBg.isChecked
            val threshold = sliderThreshold.value.toInt()

            val processed = if (removeBg) {
                ImageUtils.removeWhiteBackground(raw, threshold)
            } else {
                raw
            }
            processedSignatureBitmap = processed
            ivPreview.setImageBitmap(processed)
        }

        cbRemoveWhiteBg.setOnCheckedChangeListener { _, _ -> updateProcessedImage() }
        sliderThreshold.addOnChangeListener { _, value, _ ->
            tvThresholdVal.text = value.toInt().toString()
            updateProcessedImage()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnApply.setOnClickListener {
            val finalSig = if (activeTab == 0) {
                signaturePad.exportBitmap()
            } else {
                processedSignatureBitmap ?: selectedRawImageBitmap
            }

            if (finalSig != null) {
                binding.pdfCanvasView.addSignature(finalSig)
                selectTool(ToolMode.SELECT)
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Buat tanda tangan atau pilih gambar terlebih dahulu", Toast.LENGTH_SHORT).show()
            }
        }

        updateTabs()
        dialog.show()
    }

    private fun processImportedImageUri(uri: Uri) {
        val bitmap = ImageUtils.decodeSampledBitmapFromUri(this, uri, maxDim = 800)
        if (bitmap != null) {
            selectedRawImageBitmap = bitmap
            processedSignatureBitmap = ImageUtils.removeWhiteBackground(bitmap, 235)
            importPreviewImageView?.setImageBitmap(processedSignatureBitmap)
            noImageHintView?.visibility = View.GONE
        } else {
            Toast.makeText(this, "Gagal membaca file gambar", Toast.LENGTH_SHORT).show()
        }
    }

    // --- DIALOG: Insert Text ---
    private fun showInsertTextDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_insert_text)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val etText = dialog.findViewById<EditText>(R.id.etAnnotationText)
        val sliderSize = dialog.findViewById<Slider>(R.id.sliderFontSize)
        val tvSizeVal = dialog.findViewById<TextView>(R.id.tvFontSizeVal)
        val colorBlack = dialog.findViewById<View>(R.id.textColorBlack)
        val colorBlue = dialog.findViewById<View>(R.id.textColorBlue)
        val colorRed = dialog.findViewById<View>(R.id.textColorRed)
        val btnCancel = dialog.findViewById<MaterialButton>(R.id.btnCancelText)
        val btnApply = dialog.findViewById<MaterialButton>(R.id.btnApplyText)

        var selectedColor = Color.parseColor("#0F172A")

        sliderSize.addOnChangeListener { _, value, _ ->
            tvSizeVal.text = "${value.toInt()} sp"
        }

        colorBlack.setOnClickListener { selectedColor = Color.parseColor("#0F172A") }
        colorBlue.setOnClickListener { selectedColor = Color.parseColor("#2563EB") }
        colorRed.setOnClickListener { selectedColor = Color.parseColor("#DC2626") }

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnApply.setOnClickListener {
            val text = etText.text.toString().trim()
            if (text.isNotEmpty()) {
                val sizeSp = sliderSize.value
                binding.pdfCanvasView.addText(text, sizeSp, selectedColor)
                selectTool(ToolMode.SELECT)
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Ketikkan teks terlebih dahulu", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    // --- DIALOG: Page Tools (Rotate & Delete) ---
    private fun showPageToolsDialog() {
        val nonDeletedStates = pdfEngine.pageStates.filter { !it.isDeleted }
        if (nonDeletedStates.isEmpty()) return

        val state = nonDeletedStates[currentActivePageIndex]

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_page_tools)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val tvInfo = dialog.findViewById<TextView>(R.id.tvCurrentPageInfo)
        val btnRotate = dialog.findViewById<MaterialButton>(R.id.btnRotatePage)
        val btnDelete = dialog.findViewById<MaterialButton>(R.id.btnDeletePage)
        val btnClose = dialog.findViewById<MaterialButton>(R.id.btnClosePageTools)

        tvInfo.text = "Halaman ${currentActivePageIndex + 1} dari ${nonDeletedStates.size} (Rotasi: ${state.rotationDegrees}°)"

        btnRotate.setOnClickListener {
            state.rotationDegrees = (state.rotationDegrees + 90) % 360
            renderCurrentPage()
            dialog.dismiss()
        }

        btnDelete.setOnClickListener {
            if (nonDeletedStates.size <= 1) {
                Toast.makeText(this, "Tidak bisa menghapus satu-satunya halaman yang ada", Toast.LENGTH_SHORT).show()
            } else {
                state.isDeleted = true
                renderCurrentPage()
                dialog.dismiss()
                Toast.makeText(this, "Halaman dihapus", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // --- Export & Save PDF ---
    private fun exportPdfToUri(destinationUri: Uri) {
        val vWidth = binding.pdfCanvasView.width.toFloat()
        val vHeight = binding.pdfCanvasView.height.toFloat()
        if (vWidth <= 0 || vHeight <= 0) return

        binding.loadingLayout.visibility = View.VISIBLE
        binding.tvLoadingMsg.text = "Menyimpan dokumen PDF…"

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(destinationUri)?.use { output ->
                        pdfEngine.exportPdf(output, vWidth, vHeight) { current, total ->
                            lifecycleScope.launch(Dispatchers.Main) {
                                binding.tvLoadingMsg.text = "Memproses halaman $current dari $total…"
                            }
                        }
                    } ?: false
                } catch (_: Exception) {
                    false
                }
            }

            binding.loadingLayout.visibility = View.GONE
            if (success) {
                Toast.makeText(this@MainActivity, R.string.pdf_saved_success, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@MainActivity, "Gagal menyimpan file PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareEditedPdf() {
        val vWidth = binding.pdfCanvasView.width.toFloat()
        val vHeight = binding.pdfCanvasView.height.toFloat()
        if (vWidth <= 0 || vHeight <= 0) return

        binding.loadingLayout.visibility = View.VISIBLE
        binding.tvLoadingMsg.text = "Menyiapkan file untuk dibagikan…"

        lifecycleScope.launch {
            val shareFile = File(cacheDir, "shared_${openedFileName.removeSuffix(".pdf")}.pdf")
            val success = withContext(Dispatchers.IO) {
                try {
                    FileOutputStream(shareFile).use { output ->
                        pdfEngine.exportPdf(output, vWidth, vHeight) { _, _ -> }
                    }
                } catch (_: Exception) {
                    false
                }
            }

            binding.loadingLayout.visibility = View.GONE
            if (success && shareFile.exists()) {
                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${applicationContext.packageName}.fileprovider",
                    shareFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Bagikan Dokumen PDF"))
            } else {
                Toast.makeText(this@MainActivity, "Gagal menyiapkan file PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = it.getString(index)
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                name = name.substring(cut + 1)
            }
        }
        return name
    }

    override fun onDestroy() {
        super.onDestroy()
        pdfEngine.close()
    }
}
