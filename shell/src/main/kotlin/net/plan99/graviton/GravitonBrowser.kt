package net.plan99.graviton

import de.codecentric.centerdevice.MenuToolkit
import de.codecentric.centerdevice.dialogs.about.AboutStageBuilder
import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.StringProperty
import javafx.geometry.Pos
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.effect.DropShadow
import javafx.scene.image.Image
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.Paint
import javafx.scene.text.Font
import javafx.scene.text.TextAlignment
import javafx.stage.Stage
import javafx.stage.StageStyle
import kotlinx.coroutines.experimental.javafx.JavaFx
import kotlinx.coroutines.experimental.launch
import tornadofx.*
import java.awt.image.BufferedImage
import java.io.OutputStream
import java.io.PrintStream
import java.util.*
import javax.imageio.ImageIO
import kotlin.concurrent.thread


// TODO: Organise all attribution links for artwork in the about box, once there is one.
// icons8.com
// OFL 1.1 license for the font
// vexels for the vector art

// Allow for minimal rebranding in future.
val APP_NAME = "Graviton"
val Component.APP_LOGO get() = Image(resources["art/icons8-rocket-take-off-128.png"])

class GravitonBrowser : App(ShellView::class, Styles::class) {
    init {
        importStylesheet("/net/plan99/graviton/graviton.css")
    }

    override fun start(stage: Stage) {
        stage.icons.addAll(
                Image(resources["art/icons8-rocket-take-off-128.png"]),
                Image(resources["/net/plan99/graviton/art/icons8-rocket-take-off-512.png"]),
                Image(resources["art/icons8-rocket-take-off-64.png"])
        )
        stage.isMaximized = true
        if (currentOperatingSystem == OperatingSystem.MAC) {
            // This looks nice on OS X but not so great on other platforms. Note that it doesn't work on Java 8, but looks right on
            // Java 10. Once we upgrade we'll get it back.
            stage.initStyle(StageStyle.UNIFIED)
            val dockImage: BufferedImage = ImageIO.read(resources.stream("art/icons8-rocket-take-off-512.png"))
            try {
                // This is a PITA - stage.icons doesn't work on macOS, instead there's two other APIs, one for Java 8 and one for post-J8.
                // Try the Java 9 API first. We could also write this code out the obvious way but for now I want to be able to switch
                // back and forth between compile JDKs easily. TODO: Simplify away the reflection when I'm committed to building with Java 11.
                Class.forName("java.awt.Taskbar")
                        ?.getMethod("getTaskbar")
                        ?.invoke(null)?.let { taskbar ->
                            taskbar.javaClass
                                    .getMethod("setIconImage", java.awt.Image::class.java)
                                    .invoke(taskbar, dockImage)
                        }
            } catch (e: ClassNotFoundException) {
                try {
                    Class.forName("com.apple.eawt.Application")
                            ?.getMethod("getApplication")
                            ?.invoke(null)?.let { app ->
                                app.javaClass
                                        .getMethod("setDockIconImage", java.awt.Image::class.java)
                                        .invoke(app, dockImage)
                            }
                } catch (e: Exception) {
                    mainLog.warn("Failed to set dock icon", e)
                }
            }
            stage.title = "Graviton"
        }

        super.start(stage)
    }
}

/**
 * The main window the user interacts with to start applications.
 */
class ShellView : View() {
    companion object : Logging()

    private val downloadProgress = SimpleDoubleProperty(0.0)
    private val isWorking = SimpleBooleanProperty()
    private lateinit var spinnerAnimation: ThreeDSpinner
    private lateinit var messageText1: StringProperty
    private lateinit var messageText2: StringProperty
    private lateinit var outputArea: TextArea
    private val historyManager by lazy { HistoryManager.create() }
    private lateinit var coordinateBar: TextField

    //region Art management
    data class Art(val fileName: String, val topPadding: Int, val animationColor: Color, val topGradient: Paint)

    private val allArt = listOf(
            Art("paris.png", 200, Color.BLUE, Color.WHITE),
            Art("forest.jpg", 200,
                    Color.color(0.0, 0.5019608, 0.0, 0.5),
                    LinearGradient.valueOf("transparent,rgb(218,239,244)")
            )
    )
    private val art = allArt[1]
    //endregion

    //region UI building
    override val root = stackpane {
        style { backgroundColor = multi(Color.WHITE) }

        if (currentOperatingSystem == OperatingSystem.MAC) {
            // On macOS we can't avoid having a menu bar, and anyway it's a good place to stash an about box
            // and other such things. TODO: Consider a menu strategy on other platforms.
            setupMacMenuBar()
        }

        artVBox()
        createSpinnerAnimation()
        body()
        artCredits()
    }

    private fun StackPane.body() {
        vbox {
            pane { minHeight = 0.0 }

            // Logo
            hbox {
                alignment = Pos.CENTER
                imageview(APP_LOGO)
                label("graviton") {
                    addClass(Styles.logoText)
                }
            }

            pane { minHeight = 25.0 }

            coordinateBar()

            pane { minHeight = 25.0 }

            downloadTracker()

            pane { minHeight = 25.0 }

            recentAppsPicker()

            outputArea = textarea {
                addClass(Styles.shellArea)
                isWrapText = false
                opacity = 0.0
                textProperty().addListener { _, oldValue, newValue ->
                    if (oldValue.isBlank() && newValue.isNotBlank()) {
                        opacityProperty().animate(1.0, 0.3.seconds)
                    } else if (newValue.isBlank() && oldValue.isNotBlank()) {
                        opacityProperty().animate(0.0, 0.3.seconds)
                    }
                }
                prefRowCountProperty().bind(Bindings.`when`(textProperty().isNotEmpty).then(20).otherwise(0))
            }

            maxWidth = 1000.0
            spacing = 5.0
            alignment = Pos.TOP_CENTER
        }
    }

    private fun VBox.coordinateBar() {
        coordinateBar = textfield {
            style {
                fontSize = 20.pt
                alignment = Pos.CENTER
            }
            // When running the GUI classes standalone via TornadoFX plugin, we of course have no command line params ...
            text = try { commandLineArguments.defaultCoordinate } catch (e: UninitializedPropertyAccessException) { "" }
            selectAll()
            disableProperty().bind(isWorking)
            action { beginLaunch() }
        }
    }

    private fun VBox.downloadTracker() {
        stackpane {
            progressbar {
                fitToParentSize()
                progressProperty().bind(downloadProgress)
            }
            vbox {
                addClass(Styles.messageBox)
                padding = insets(15.0)
                alignment = Pos.CENTER
                label {
                    messageText1 = textProperty()
                    textAlignment = TextAlignment.CENTER
                }
                label {
                    messageText2 = textProperty()
                    textAlignment = TextAlignment.CENTER
                    style {
                        fontSize = 15.pt
                    }
                }
            }
        }.also {
            // If we're not downloading, hide this chunk of UI and take it out of layout.
            val needed = messageText1.isNotEmpty.or(messageText2.isNotEmpty)
            it.visibleProperty().bind(needed)
            it.managedProperty().bind(needed)
        }
    }

    private fun StackPane.artCredits() {
        label("Background art by Vexels") {
            style {
                padding = box(10.px)
            }
        }.stackpaneConstraints { alignment = Pos.BOTTOM_RIGHT }
    }

    private fun StackPane.createSpinnerAnimation() {
        spinnerAnimation = ThreeDSpinner(art.animationColor)
        spinnerAnimation.root.maxWidth = 600.0
        spinnerAnimation.root.maxHeight = 600.0
        spinnerAnimation.root.translateY = 0.0
        children += spinnerAnimation.root
        spinnerAnimation.visible.bind(isWorking)
    }

    private fun StackPane.artVBox() {
        vbox {
            stackpane {
                style {
                    backgroundColor = multi(art.topGradient)
                }
                vbox {
                    minHeight = art.topPadding.toDouble()
                }
            }
            // Background image.
            imageview {
                image = Image(resources["art/${art.fileName}"])
                fitWidthProperty().bind(this@artVBox.widthProperty())
                isPreserveRatio = true

                // This line is useful when fiddling with the animation:
                // setOnMouseClicked { isDownloading.set(!isDownloading.value) }
            }.stackpaneConstraints {
                alignment = Pos.BOTTOM_CENTER
            }
        }.stackpaneConstraints { alignment = Pos.TOP_CENTER }
    }

    private fun VBox.recentAppsPicker() {
        for (entry: HistoryEntry in historyManager.history) {
            vbox {
                addClass(Styles.historyEntry)
                label(entry.name) { addClass(Styles.historyTitle) }
                label(entry.description ?: "")

                setOnMouseClicked {
                    coordinateBar.text = entry.coordinateFragment
                    beginLaunch()
                }
            }
        }
    }

    private fun setupMacMenuBar() {
        val tk = MenuToolkit.toolkit()
        val aboutStage = AboutStageBuilder
                .start("About $APP_NAME")
                .withAppName(APP_NAME)
                .withCloseOnFocusLoss()
                .withVersionString("Version $gravitonShellVersionNum")
                .withCopyright("Copyright \u00A9 " + Calendar.getInstance().get(Calendar.YEAR))
                .withImage(APP_LOGO)
                .build()

        menubar {
            val appMenu = menu(APP_NAME) {
                // Note that the app menu name can't be changed at runtime and will be ignored; to make the menu bar say Graviton
                // requires bundling it. During development it will just say 'java' and that's OK.
                this += tk.createAboutMenuItem(APP_NAME, aboutStage)
                separator()
                item("Clear cache ...") {
                    setOnAction {
                        historyManager.clearCache()
                        Alert(Alert.AlertType.INFORMATION, "Cache has been cleared. Apps will re-download next time they are " +
                                "invoked or a background update occurs.", ButtonType.CLOSE).showAndWait()
                    }
                }
                separator()
                this += tk.createQuitMenuItem(APP_NAME)
            }
            tk.setApplicationMenu(appMenu);
        }
    }
    //endregion

    //region Event handling
    private fun beginLaunch() {
        val text = coordinateBar.text
        if (text.isBlank()) return

        // We animate even if there's no downloading to do because for complex apps, simply resolving dependency graphs and starting the
        // app can take a bit of time.
        isWorking.set(true)

        // Parse what the user entered as if it were a command line: this feature is a bit of an easter egg,
        // but makes testing a lot easier, e.g. to force a re-download just put --clear-cache at the front.
        val cmdLineParams = app.parameters.raw.joinToString(" ")
        val options = GravitonCLI.parse("$cmdLineParams $text")

        // These callbacks will run on the FX event thread.
        val events = object : AppLauncher.Events {
            override suspend fun onStartedDownloading(name: String) {
                downloadProgress.set(0.0)
                if (name.contains("maven-metadata-")) {
                    messageText1.set("Checking for updates")
                    messageText2.set("")
                    return
                }
                messageText1.set("Downloading")
                messageText2.set(name)
            }

            var progress = 0.0

            override suspend fun onFetch(name: String, totalBytesToDownload: Long, totalDownloadedSoFar: Long) {
                if (name.contains("maven-metadata-")) {
                    messageText1.set("Checking for updates")
                    messageText2.set("")
                    return
                }
                messageText1.set("Downloading")
                messageText2.set(name)
                val pr = totalDownloadedSoFar.toDouble() / totalBytesToDownload.toDouble()
                // Need to make sure progress only jumps backwards if we genuinely have a big correction.
                if (pr - progress < 0 && Math.abs(pr - progress) < 0.2) return
                progress = pr
                downloadProgress.set(progress)
            }

            override suspend fun onStoppedDownloading() {
                downloadProgress.set(1.0)
                messageText1.set("")
                messageText2.set("")
            }

            override suspend fun aboutToStartApp() {
                isWorking.set(false)
                messageText1.set("")
                messageText2.set("")
            }
        }

        // Capture the output of the program and redirect it to a text area. In future we'll switch this to be a real
        // terminal and get rid of it for graphical apps.
        outputArea.text = ""
        val printStream = PrintStream(object : OutputStream() {
            override fun write(b: Int) {
                Platform.runLater {
                    outputArea.text += b.toChar()
                }
            }
        }, true)

        // Now start a coroutine that will run everything on the FX thread other than background tasks.
        launch(JavaFx) {
            try {
                AppLauncher(options, historyManager, primaryStage, JavaFx, events, printStream, printStream).start()
            } catch (e: Throwable) {
                onStartError(e)
                coordinateBar.selectAll()
                coordinateBar.requestFocus()
            }
        }
    }

    private fun onStartError(e: Throwable) {
        isWorking.set(false)
        downloadProgress.set(0.0)
        messageText1.set("Start failed")
        messageText2.set(e.message)
        logger.error("Start failed", e)
    }

    private fun mockDownload() {
        isWorking.set(true)
        downloadProgress.set(0.0)
        messageText1.set("Mock downloading ..")
        thread {
            Thread.sleep(5000)
            Platform.runLater {
                downloadProgress.animate(1.0, 5000.millis) {
                    setOnFinished {
                        isWorking.set(false)
                        messageText1.set("")
                        messageText2.set("")
                    }
                }
            }
        }
    }
    //endregion
}

class Styles : Stylesheet() {
    companion object {
        val shellArea by cssclass()
        val content by cssclass()
        val logoText by cssclass()
        val messageBox by cssclass()
        val historyEntry by cssclass()
        val historyTitle by cssclass()
    }

    private val wireFont: Font = loadFont("/net/plan99/graviton/art/Wire One regular.ttf", 25.0)!!

    init {
        val cornerRadius = multi(box(10.px))

        shellArea {
            fontFamily = "monospace"
            fontSize = 15.pt
            borderColor = multi(box(Color.gray(0.8, 1.0)))
            borderWidth = multi(box(3.px))
            borderRadius = cornerRadius
            backgroundColor = multi(Color.color(1.0, 1.0, 1.0, 0.95))
            backgroundRadius = cornerRadius
            scrollPane {
                content {
                    backgroundColor = multi(Color.TRANSPARENT)
                }
                viewport {
                    backgroundColor = multi(Color.TRANSPARENT)
                }
            }
        }

        logoText {
            font = wireFont
            fontSize = 120.pt
            effect = DropShadow(15.0, Color.WHITE)
        }

        messageBox {
            backgroundColor = multi(Color.color(1.0, 1.0, 1.0, 0.9))
            backgroundRadius = multi(box(5.px))
            borderWidth = multi(box(3.px))
            borderColor = multi(box(Color.LIGHTGREY))
            borderRadius = multi(box(5.px))
            fontSize = 25.pt
        }

        historyEntry {
            borderWidth = multi(box(2.px))
            borderColor = multi(box(Color.web("#dddddd")))
            borderRadius = cornerRadius
            backgroundColor = multi(LinearGradient.valueOf("white,#eeeeeedd"))
            backgroundRadius = cornerRadius
            padding = box(20.px)
        }

        historyEntry and hover {
            borderColor = multi(box(Color.web("#555555")))
            cursor = javafx.scene.Cursor.HAND
        }

        historyTitle {
            fontSize = 25.pt
            padding = box(0.px, 0.px, 15.pt, 0.px)
        }
    }
}