import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.PathIterator
import java.awt.image.BufferedImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.imageio.ImageIO

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.FFTT04M"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.FFTT04M"
        // Lowered 32 → 23 to unify with the retired L (Legacy) variant: one APK installs on old devices
        // too. Modern features gate at RUNTIME (DeviceCaps tiers + the Wi-Fi HuBERT download), not at install.
        minSdk = 23
        targetSdk = 36
        // Versioning starts at major 2 with a build timestamp; "M" prefix marks this as the M variant.
        // versionCode = whole minutes since the Unix epoch (monotonic, fits Int until ~2065).
        versionCode = (System.currentTimeMillis() / 60000L).toInt()
        versionName = "M2.${SimpleDateFormat("yyMMdd.HHmm", Locale.US).format(Date())}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Public HuBERT model (Meta, exported to ONNX) is NO LONGER bundled — it downloads over Wi-Fi
        // and HuBERT features stay disabled (DSP fallback) until it lands. Only OUR proprietary codebooks
        // ship in the APK. Set the host with -PhubertUrl=... (a GitHub Release asset ≤2 GB works).
        // The app verifies the download against the exact size + sha256 before ORT loads it.
        val hubertUrl = (project.findProperty("hubertUrl") as String?)
            ?: "https://github.com/523690-create/FFTT04M/releases/download/hubert-base-v1/hubert_base.onnx"
        buildConfigField("String", "HUBERT_MODEL_URL", "\"$hubertUrl\"")
        buildConfigField("String", "HUBERT_MODEL_SHA256", "\"e975dac7930a807e8989b147eab95496df6ff47614aa87afd4d86da77bb28179\"")
        buildConfigField("long", "HUBERT_MODEL_BYTES", "377727100L")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // Backport java.util default methods (e.g. List.sort(Comparator), used by ZXing) to API 23.
        // Without this the QR scanner throws NoSuchMethodError on the Nexus 7 (API 23).
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        disable.add("MissingPermission")
        abortOnError = false
    }

    // Don't compress the bundled HuBERT model — it's already dense (compression wastes build time and
    // gains almost nothing), and leaving it stored lets the staged copy / mmap stay cheap.
    androidResources {
        noCompress += "onnx"
    }
}

// Rename APK
project.afterEvaluate {
    val extension = project.extensions.getByName("android")
    if (extension is com.android.build.gradle.AppExtension) {
        extension.applicationVariants.all {
            outputs.all {
                if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                    val timestamp = SimpleDateFormat("yyyyMMdd-HHmm").format(Date())
                    outputFileName = "FFTT04M-$timestamp.apk"
                }
            }
        }
    }
}

// ---- Debug version-letter launcher icons ----------------------------------------------------
// Each DEBUG build stamps the next letter (a..z, A..Z, cycling) into the icon's inner circle so you
// can tell at a glance which build is on a device. Magenta letter (matches the outer ring) centered
// in the cyan inner circle. Regenerates the vector foreground (covers both the API26 adaptive icon
// AND the API21 layer-list, since both reference @drawable/ic_launcher_foreground) plus the legacy
// density rasters. Release builds are untouched. The counter lives in a gitignored file.

val iconResDir = layout.buildDirectory.dir("generated/debugIcons/res").get().asFile
android.sourceSets.getByName("debug").res.srcDir(iconResDir)

val letterSequence: List<Char> = (('a'..'z') + ('A'..'Z'))

fun fmt(v: Double): String = String.format(Locale.US, "%.3f", v)

/** First installed cursive/script family, with sensible Windows/cross-platform fallbacks. */
fun cursiveFontName(): String {
    System.setProperty("java.awt.headless", "true")
    val fams = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toHashSet()
    return listOf("Segoe Script", "Brush Script MT", "Lucida Handwriting", "Ink Free",
                  "Gabriola", "Comic Sans MS", "Mistral", "Freestyle Script").firstOrNull { it in fams }
        ?: Font.SERIF   // fall back to serif if no script font is installed
}

/** The letter sequence recycles a..z,A..Z every 52 builds; each 52-build generation changes the
 *  typeface: 0 = sans-serif, 1 = serif, 2 = cursive/script, then repeats. */
fun fontFamilyForIndex(idx: Int): String = when ((idx / 52) % 3) {
    0 -> Font.SANS_SERIF
    1 -> Font.SERIF
    else -> cursiveFontName()
}

/** The version letter's glyph as Android vector pathData, scaled to ~30 units tall and centered at
 *  (54,54) — comfortably inside the cyan inner circle (radius 20) of the 108-unit icon viewport. */
fun glyphPathData(letter: Char, fontName: String): String {
    System.setProperty("java.awt.headless", "true")
    val font = Font(fontName, Font.BOLD, 100)
    val outline = font.createGlyphVector(FontRenderContext(null, true, true), letter.toString()).outline
    val b = outline.bounds2D
    // Scale by the glyph's DIAGONAL so wide letters (W/M) shrink to clear the circle and narrow
    // ones (I/l) aren't needlessly small. Target diagonal 32 → bbox corners sit at radius 16,
    // comfortably inside the cyan inner circle (radius 20).
    val scale = 32.0 / Math.hypot(b.width, b.height)
    fun tx(x: Double) = 54.0 + (x - b.centerX) * scale
    fun ty(y: Double) = 54.0 + (y - b.centerY) * scale
    val sb = StringBuilder()
    val it = outline.getPathIterator(null)
    val c = DoubleArray(6)
    while (!it.isDone) {
        when (it.currentSegment(c)) {
            PathIterator.SEG_MOVETO -> sb.append("M${fmt(tx(c[0]))},${fmt(ty(c[1]))} ")
            PathIterator.SEG_LINETO -> sb.append("L${fmt(tx(c[0]))},${fmt(ty(c[1]))} ")
            PathIterator.SEG_QUADTO -> sb.append("Q${fmt(tx(c[0]))},${fmt(ty(c[1]))} ${fmt(tx(c[2]))},${fmt(ty(c[3]))} ")
            PathIterator.SEG_CUBICTO -> sb.append("C${fmt(tx(c[0]))},${fmt(ty(c[1]))} ${fmt(tx(c[2]))},${fmt(ty(c[3]))} ${fmt(tx(c[4]))},${fmt(ty(c[5]))} ")
            PathIterator.SEG_CLOSE -> sb.append("Z ")
        }
        it.next()
    }
    return sb.toString().trim()
}

fun foregroundVectorXml(letter: Char, fontName: String): String = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FF00FF" android:pathData="M54,54m-40,0a40,40 0,1 1,80 0a40,40 0,1 1,-80 0" />
    <path android:fillColor="#00FFFF" android:pathData="M54,54m-20,0a20,20 0,1 1,40 0a20,20 0,1 1,-40 0" />
    <path android:fillColor="#FF00FF" android:pathData="${glyphPathData(letter, fontName)}" />
</vector>
"""

/** Full raster icon (black bg, magenta outer disk, cyan inner disk, magenta letter) for legacy. */
fun renderIconRaster(letter: Char, size: Int, round: Boolean, fontName: String): BufferedImage {
    System.setProperty("java.awt.headless", "true")
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    val s = size.toDouble()
    fun px(v: Double) = v / 108.0 * s
    fun disk(cx: Double, cy: Double, r: Double) = Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2)
    g.color = Color.BLACK
    if (round) g.fill(disk(s / 2, s / 2, s / 2)) else g.fillRect(0, 0, size, size)
    g.color = Color(0xFF, 0x00, 0xFF); g.fill(disk(px(54.0), px(54.0), px(40.0)))
    g.color = Color(0x00, 0xFF, 0xFF); g.fill(disk(px(54.0), px(54.0), px(20.0)))
    g.color = Color(0xFF, 0x00, 0xFF)
    val outline = Font(fontName, Font.BOLD, 100)
        .createGlyphVector(g.fontRenderContext, letter.toString()).outline
    val b = outline.bounds2D
    val sc = px(32.0) / Math.hypot(b.width, b.height)   // diagonal-based fit (see glyphPathData)
    val at = AffineTransform()
    at.translate(px(54.0), px(54.0))
    at.scale(sc, sc)
    at.translate(-b.centerX, -b.centerY)
    g.fill(at.createTransformedShape(outline))
    g.dispose()
    return img
}

val generateDebugIcons = tasks.register("generateDebugIcons") {
    val outDir = iconResDir
    val counterFile = file("icon_letter_index.txt")
    outputs.dir(outDir)
    outputs.upToDateWhen { false }   // advance the letter on every debug build
    doLast {
        val idx = counterFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        val letter = letterSequence[((idx % 52) + 52) % 52]
        val fontName = fontFamilyForIndex(idx)
        val fontGen = listOf("sans", "serif", "cursive")[(idx / 52) % 3]
        counterFile.writeText((idx + 1).toString())
        File(outDir, "drawable").apply { mkdirs() }
            .resolve("ic_launcher_foreground.xml").writeText(foregroundVectorXml(letter, fontName))
        mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)
            .forEach { (q, sz) ->
                val dir = File(outDir, "mipmap-$q").apply { mkdirs() }
                ImageIO.write(renderIconRaster(letter, sz, false, fontName), "png", File(dir, "ic_launcher.png"))
                ImageIO.write(renderIconRaster(letter, sz, true, fontName), "png", File(dir, "ic_launcher_round.png"))
            }
        println("Debug launcher icon letter: '$letter' (build index $idx, $fontGen font '$fontName')")
    }
}

afterEvaluate {
    listOf("preDebugBuild", "mergeDebugResources", "generateDebugResources",
           "mapDebugSourceSetPaths", "parseDebugLocalResources")
        .forEach { name -> tasks.findByName(name)?.dependsOn(generateDebugIcons) }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    // QR generate/scan for device-to-device gallery transfer (pure Java, no Play Services).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // On-device HuBERT inference (capable/Tier-2 phones only — see HubertProbe). ~15 MB native libs.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}