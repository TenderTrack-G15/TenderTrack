package za.ac.tendertrack.ui.screens.phases

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.core.friendlyMessage
import za.ac.tendertrack.data.model.PhasePhoto
import za.ac.tendertrack.data.repo.PhaseRepository
import za.ac.tendertrack.ui.components.AppIcon
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

/*
 * Photo proof: taking a photo with the phone's camera, and showing photos.
 *
 * Proof is camera only. The camera app is opened with ACTION_IMAGE_CAPTURE
 * (ActivityResultContracts.TakePicture) and there is no way to pick a file or
 * an existing picture from the gallery. The photo is turned the right way up,
 * made smaller (longest side 1600 pixels) and sent to the database as a JPEG,
 * which checks it again.
 *
 * No camera permission is needed: the phone's own camera app takes the photo.
 * Don't add android.permission.CAMERA to AndroidManifest.xml. If it is there,
 * Android requires the app to ask for it first, and the camera won't open.
 */

/** A photo ready to send: the JPEG as base64, and a preview to show before sending. */
class PreparedPhoto(val base64: String, val preview: ImageBitmap, val sizeBytes: Int)

object PhotoProof {
    private const val MAX_SIDE = 1600
    private const val QUALITY = 82
    private const val FOLDER = "photos"

    /** A new, empty file for the camera to write into (in the app's cache, see res/xml/file_paths.xml). */
    fun newCaptureFile(context: Context): File {
        val dir = File(context.cacheDir, FOLDER).apply { mkdirs() }
        // Photos are deleted after sending; this clears any left behind by a crash.
        val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        dir.listFiles()?.filter { it.lastModified() < dayAgo }?.forEach { it.delete() }
        return File(dir, "phase_${System.currentTimeMillis()}.jpg")
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Turns the camera's file into a smaller JPEG, the right way up. */
    fun prepare(file: File): PreparedPhoto {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The camera did not return a photo. Take it again." }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("The photo could not be read. Take it again.")

        val rotation = when (
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val scale = min(1f, MAX_SIDE.toFloat() / max(decoded.width, decoded.height))
        val upright = if (scale < 1f || rotation != 0f) {
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postRotate(rotation)
            }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } else decoded

        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        val bytes = out.toByteArray()
        return PreparedPhoto(Base64.encodeToString(bytes, Base64.NO_WRAP), upright.asImageBitmap(), bytes.size)
    }

    /** A photo from the database (base64 JPEG) as something Compose can draw. */
    fun decode(base64: String): ImageBitmap? = try {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

/**
 * Returns a function that opens the camera. When a photo is taken, [onPhoto]
 * receives it, ready to preview and send. Nothing else can be chosen: there is
 * no gallery or file option.
 */
@Composable
fun rememberCameraCapture(onPhoto: (PreparedPhoto) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Kept across rotation and if Android pauses the app while the camera is open.
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    val currentOnPhoto by rememberUpdatedState(onPhoto)
    val currentOnError by rememberUpdatedState(onError)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val path = pendingPath
        pendingPath = null
        if (path == null) return@rememberLauncherForActivityResult
        val file = File(path)
        if (!taken) {
            file.delete()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { PhotoProof.prepare(file) } }
            file.delete()
            result.onSuccess { currentOnPhoto(it) }
                .onFailure { currentOnError(it.message ?: "The photo could not be read. Take it again.") }
        }
    }

    return {
        try {
            val file = PhotoProof.newCaptureFile(context)
            pendingPath = file.absolutePath
            launcher.launch(PhotoProof.uriFor(context, file))
        } catch (e: ActivityNotFoundException) {
            pendingPath = null
            currentOnError("This phone has no camera app that TenderTrack can open.")
        } catch (e: Exception) {
            pendingPath = null
            currentOnError(e.friendlyMessage())
        }
    }
}

/**
 * One photo in a frame, with a label above it and a caption below. Tapping it
 * opens it full screen. With no photo, shows [emptyText] instead.
 */
@Composable
fun PhotoFrame(
    label: String,
    bitmap: ImageBitmap?,
    modifier: Modifier = Modifier,
    caption: String? = null,
    emptyText: String = "No photo yet",
    loading: Boolean = false
) {
    var enlarged by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label.uppercase(), style = AppType.Eyebrow)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(Dimens.RadiusField))
                .background(AppColor.SurfaceMuted)
                .border(Dimens.BorderWidth, AppColor.Line, RoundedCornerShape(Dimens.RadiusField))
                .then(if (bitmap != null) Modifier.clickable { enlarged = true } else Modifier),
            contentAlignment = Alignment.Center
        ) {
            when {
                bitmap != null -> Image(
                    bitmap = bitmap,
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                loading -> CircularProgressIndicator(color = AppColor.Ink, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppIcon(Icons.Default.PhotoCamera, tint = AppColor.MutedLight, size = 26.dp)
                    Text(emptyText, style = AppType.Tiny.copy(color = AppColor.Muted), modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
        if (caption != null) Text(caption, style = AppType.Tiny.copy(color = AppColor.Muted))
    }
    if (enlarged && bitmap != null) PhotoViewer(label, bitmap) { enlarged = false }
}

/** A photo kept in the database, loaded when shown. */
@Composable
fun StoredPhotoFrame(
    label: String,
    photoId: String?,
    modifier: Modifier = Modifier,
    emptyText: String = "No photo yet",
    repository: PhaseRepository = PhaseRepository.instance
) {
    val loaded by produceState<Pair<PhasePhoto?, ImageBitmap?>?>(initialValue = null, photoId) {
        value = if (photoId == null) Pair(null, null) else try {
            val photo = repository.photo(photoId)
            Pair(photo, withContext(Dispatchers.Default) { PhotoProof.decode(photo.photo) })
        } catch (e: Exception) {
            Pair(null, null)
        }
    }
    val photo = loaded?.first
    PhotoFrame(
        label = label,
        bitmap = loaded?.second,
        modifier = modifier,
        caption = photo?.let { listOfNotNull(Format.dateTime(it.takenAt), it.takenByName.ifBlank { null }).joinToString(" · ") },
        emptyText = if (photoId != null && loaded != null && photo == null) "The photo could not be loaded" else emptyText,
        loading = photoId != null && loaded == null
    )
}

/** Full-screen view of one photo. Tap anywhere or the cross to close. */
@Composable
fun PhotoViewer(title: String, bitmap: ImageBitmap, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { onDismiss() }
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .background(Color(0x99000000))
                    .padding(horizontal = Dimens.ScreenPadding, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = AppType.Body.copy(color = Color.White), modifier = Modifier.weight(1f))
                AppIcon(Icons.Default.Close, tint = Color.White, size = 22.dp, contentDescription = "Close")
            }
        }
    }
}
