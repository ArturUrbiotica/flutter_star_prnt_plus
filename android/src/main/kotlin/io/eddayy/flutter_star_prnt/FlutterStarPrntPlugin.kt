package io.eddayy.flutter_star_prnt

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.webkit.URLUtil
import androidx.annotation.NonNull
import com.starmicronics.stario.PortInfo
import com.starmicronics.stario.StarIOPort
import com.starmicronics.stario.StarPrinterStatus
import com.starmicronics.starioextension.ICommandBuilder
import com.starmicronics.starioextension.ICommandBuilder.*
import com.starmicronics.starioextension.IConnectionCallback
import com.starmicronics.starioextension.StarIoExt
import com.starmicronics.starioextension.StarIoExt.Emulation
import com.starmicronics.starioextension.StarIoExtManager
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.nio.charset.Charset
import java.nio.charset.UnsupportedCharsetException

/** FlutterStarPrntPlugin (embedding v2) */
class FlutterStarPrntPlugin : FlutterPlugin, MethodCallHandler {

    // Contexto y canal del plugin
    private lateinit var applicationContext: Context
    private lateinit var channel: MethodChannel

    // Gestor de conexión StarIO (si se usa connect/disconnect)
    private var starIoExtManager: StarIoExtManager? = null

    // --- FlutterPlugin ---

    override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "flutter_star_prnt")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        try {
            starIoExtManager?.disconnect(object : IConnectionCallback {
                override fun onConnected(connectResult: IConnectionCallback.ConnectResult) {}
                override fun onDisconnected() {}
            })
        } catch (_: Exception) {}
        starIoExtManager = null
    }

    // --- Method channel ---

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull rawResult: Result) {
        val result = MethodResultWrapper(rawResult)
        // No bloquear el hilo principal
        Thread(MethodRunner(call, result)).start()
    }

    // Ejecuta métodos en background
    inner class MethodRunner(private val call: MethodCall, private val result: Result) : Runnable {
        override fun run() {
            when (call.method) {
                "portDiscovery" -> portDiscovery(call, result)
                "checkStatus"   -> checkStatus(call, result)
                "print"         -> print(call, result)
                "connect"       -> connect(call, result) // por si tu Dart lo usa
                else            -> result.notImplemented()
            }
        }
    }

    // Envuelve resultados en el hilo principal
    class MethodResultWrapper(private val methodResult: Result) : Result {
        private val handler = Handler(Looper.getMainLooper())

        override fun success(result: Any?) {
            handler.post { methodResult.success(result) }
        }

        override fun error(code: String, msg: String?, details: Any?) {
            handler.post { methodResult.error(code, msg, details) }
        }

        override fun notImplemented() {
            handler.post { methodResult.notImplemented() }
        }
    }

    // --- API expuesta al Dart ---

    fun portDiscovery(@NonNull call: MethodCall, @NonNull result: Result) {
        val strInterface = call.argument<String>("type") ?: "All"
        try {
            val response =
                when (strInterface) {
                    "LAN"        -> getPortDiscovery("LAN")
                    "Bluetooth"  -> getPortDiscovery("Bluetooth")
                    "USB"        -> getPortDiscovery("USB")
                    else         -> getPortDiscovery("All")
                }
            result.success(response)
        } catch (e: Exception) {
            result.error("PORT_DISCOVERY_ERROR", e.message, null)
        }
    }

    fun checkStatus(@NonNull call: MethodCall, @NonNull result: Result) {
        val portName   = call.argument<String>("portName") ?: return result.error("ARG_ERROR", "portName null", null)
        val emulation  = call.argument<String>("emulation") ?: return result.error("ARG_ERROR", "emulation null", null)

        var port: StarIOPort? = null
        try {
            val portSettings = getPortSettingsOption(emulation)
            port = StarIOPort.getPort(portName, portSettings, 10000, applicationContext)
            try { Thread.sleep(500) } catch (_: InterruptedException) {}

            val status: StarPrinterStatus = port.retreiveStatus()
            val json: MutableMap<String, Any?> = mutableMapOf(
                "is_success" to true,
                "offline" to status.offline,
                "coverOpen" to status.coverOpen,
                "overTemp" to status.overTemp,
                "cutterError" to status.cutterError,
                "receiptPaperEmpty" to status.receiptPaperEmpty
            )
            try {
                val fw = port.firmwareInformation
                json["ModelName"] = fw["ModelName"]
                json["FirmwareVersion"] = fw["FirmwareVersion"]
            } catch (e: Exception) {
                json["error_message"] = e.message
            }
            result.success(json)
        } catch (e: Exception) {
            result.error("CHECK_STATUS_ERROR", e.message, null)
        } finally {
            port?.let {
                try { StarIOPort.releasePort(it) } catch (_: Exception) {}
            }
        }
    }

    fun connect(@NonNull call: MethodCall, @NonNull result: Result) {
        val portName        = call.argument<String>("portName") ?: return result.error("ARG_ERROR", "portName null", null)
        val emulation       = call.argument<String>("emulation") ?: return result.error("ARG_ERROR", "emulation null", null)
        val hasBarcodeReader= call.argument<Boolean>("hasBarcodeReader") ?: false

        val portSettings = getPortSettingsOption(emulation)
        try {
            var manager = starIoExtManager

            if (manager?.port != null) {
                manager.disconnect(object : IConnectionCallback {
                    override fun onConnected(connectResult: IConnectionCallback.ConnectResult) {}
                    override fun onDisconnected() {}
                })
            }

            manager = StarIoExtManager(
                if (hasBarcodeReader) StarIoExtManager.Type.WithBarcodeReader else StarIoExtManager.Type.Standard,
                portName,
                portSettings,
                10000,
                applicationContext
            )

            starIoExtManager = manager

            manager.connect(object : IConnectionCallback {
                override fun onConnected(connectResult: IConnectionCallback.ConnectResult) {
                    if (connectResult == IConnectionCallback.ConnectResult.Success ||
                        connectResult == IConnectionCallback.ConnectResult.AlreadyConnected) {
                        result.success("Printer Connected")
                    } else {
                        result.error("CONNECT_ERROR", "Error Connecting to the printer", null)
                    }
                }
                override fun onDisconnected() { /* no-op */ }
            })
        } catch (e: Exception) {
            result.error("CONNECT_ERROR", e.message, e)
        }
    }

    fun print(@NonNull call: MethodCall, @NonNull result: Result) {
        val portName   = call.argument<String>("portName") ?: return result.error("ARG_ERROR", "portName null", null)
        val emulation  = call.argument<String>("emulation") ?: return result.error("ARG_ERROR", "emulation null", null)
        val printCommands = call.argument<ArrayList<Map<String, Any>>>("printCommands") ?: arrayListOf()

        if (printCommands.isEmpty()) {
            result.success(mapOf(
                "offline" to false,
                "coverOpen" to false,
                "cutterError" to false,
                "receiptPaperEmpty" to false,
                "info_message" to "No data to print",
                "is_success" to true
            ))
            return
        }

        val builder: ICommandBuilder = StarIoExt.createCommandBuilder(getEmulation(emulation))
        builder.beginDocument()
        appendCommands(builder, printCommands, applicationContext)
        builder.endDocument()
        sendCommand(portName, getPortSettingsOption(emulation), builder.commands, applicationContext, result)
    }

    // --- Helpers ---

    private fun getPortDiscovery(@NonNull interfaceName: String): MutableList<Map<String, String>> {
        val arrayDiscovery = mutableListOf<PortInfo>()
        val arrayPorts     = mutableListOf<Map<String, String>>()

        if (interfaceName == "Bluetooth" || interfaceName == "All") {
            for (portInfo in StarIOPort.searchPrinter("BT:")) arrayDiscovery.add(portInfo)
        }
        if (interfaceName == "LAN" || interfaceName == "All") {
            for (port in StarIOPort.searchPrinter("TCP:")) arrayDiscovery.add(port)
        }
        if (interfaceName == "USB" || interfaceName == "All") {
            try {
                for (port in StarIOPort.searchPrinter("USB:", applicationContext)) arrayDiscovery.add(port)
            } catch (e: Exception) { Log.e("FlutterStarPrnt", "usb not connected", e) }
        }

        for (discovery in arrayDiscovery) {
            val port = mutableMapOf<String, String>()
            if (discovery.portName.startsWith("BT:"))
                port["portName"] = "BT:" + discovery.macAddress
            else
                port["portName"] = discovery.portName

            if (discovery.macAddress.isNotEmpty()) {
                port["macAddress"] = discovery.macAddress
                if (discovery.portName.startsWith("BT:")) {
                    port["modelName"] = discovery.portName
                } else if (discovery.modelName.isNotEmpty()) {
                    port["modelName"] = discovery.modelName
                }
            } else if (interfaceName == "USB" || interfaceName == "All") {
                if (discovery.modelName.isNotEmpty()) port["modelName"] = discovery.modelName
                if (discovery.usbSerialNumber != " SN:") port["USBSerialNumber"] = discovery.usbSerialNumber
            }
            arrayPorts.add(port)
        }
        return arrayPorts
    }

    private fun getPortSettingsOption(emulation: String): String =
        when (emulation) {
            "EscPosMobile"       -> "mini"
            "EscPos"             -> "escpos"
            "StarPRNT", "StarPRNTL" -> "Portable;l"
            else -> emulation
        }

    private fun getEmulation(emulation: String?): Emulation =
        when (emulation) {
            "StarPRNT"       -> Emulation.StarPRNT
            "StarPRNTL"      -> Emulation.StarPRNTL
            "StarLine"       -> Emulation.StarLine
            "StarGraphic"    -> Emulation.StarGraphic
            "EscPos"         -> Emulation.EscPos
            "EscPosMobile"   -> Emulation.EscPosMobile
            "StarDotImpact"  -> Emulation.StarDotImpact
            else             -> Emulation.StarLine
        }

    private fun appendCommands(
        builder: ICommandBuilder,
        printCommands: ArrayList<Map<String, Any>>?,
        context: Context
    ) {
        var encoding: Charset = Charset.forName("US-ASCII")

        printCommands?.forEach {
            when {
                it.containsKey("appendCharacterSpace") -> builder.appendCharacterSpace(it["appendCharacterSpace"].toString().toInt())
                it.containsKey("appendEncoding")       -> encoding = getEncoding(it["appendEncoding"].toString())
                it.containsKey("appendCodePage")       -> builder.appendCodePage(getCodePageType(it["appendCodePage"].toString()))
                it.containsKey("append")               -> builder.append(it["append"].toString().toByteArray(encoding))
                it.containsKey("appendRaw")            -> builder.append(it["appendRaw"].toString().toByteArray(encoding))
                it.containsKey("appendMultiple")       -> builder.appendMultiple(it["appendMultiple"].toString().toByteArray(encoding), 2, 2)
                it.containsKey("appendEmphasis")       -> builder.appendEmphasis(it["appendEmphasis"].toString().toByteArray(encoding))
                it.containsKey("enableEmphasis")       -> builder.appendEmphasis(it["enableEmphasis"].toString().toBoolean())
                it.containsKey("appendInvert")         -> builder.appendInvert(it["appendInvert"].toString().toByteArray(encoding))
                it.containsKey("enableInvert")         -> builder.appendInvert(it["enableInvert"].toString().toBoolean())
                it.containsKey("appendUnderline")      -> builder.appendUnderLine(it["appendUnderline"].toString().toByteArray(encoding))
                it.containsKey("enableUnderline")      -> builder.appendUnderLine(it["enableUnderline"].toString().toBoolean())
                it.containsKey("appendInternational")  -> builder.appendInternational(getInternational(it["appendInternational"].toString()))
                it.containsKey("appendLineFeed")       -> builder.appendLineFeed(it["appendLineFeed"] as Int)
                it.containsKey("appendUnitFeed")       -> builder.appendUnitFeed(it["appendUnitFeed"] as Int)
                it.containsKey("appendLineSpace")      -> builder.appendLineSpace(it["appendLineSpace"] as Int)
                it.containsKey("appendFontStyle")      -> builder.appendFontStyle(getFontStyle(it["appendFontStyle"] as String))
                it.containsKey("appendCutPaper")       -> builder.appendCutPaper(getCutPaperAction(it["appendCutPaper"].toString()))
                it.containsKey("openCashDrawer")       -> builder.appendPeripheral(getPeripheralChannel(it["openCashDrawer"] as Int))
                it.containsKey("appendBlackMark")      -> builder.appendBlackMark(getBlackMarkType(it["appendBlackMark"].toString()))
                it.containsKey("appendBytes")          -> builder.append(it["appendBytes"].toString().toByteArray(encoding))
                it.containsKey("appendRawBytes")       -> builder.appendRaw(it["appendRawBytes"].toString().toByteArray(encoding))

                it.containsKey("appendAbsolutePosition") -> {
                    if (it.containsKey("data")) {
                        builder.appendAbsolutePosition(
                            it["data"].toString().toByteArray(encoding),
                            it["appendAbsolutePosition"].toString().toInt()
                        )
                    } else {
                        builder.appendAbsolutePosition(it["appendAbsolutePosition"].toString().toInt())
                    }
                }

                it.containsKey("appendAlignment") -> {
                    if (it.containsKey("data")) {
                        builder.appendAlignment(
                            it["data"].toString().toByteArray(encoding),
                            getAlignment(it["appendAlignment"].toString())
                        )
                    } else {
                        builder.appendAlignment(getAlignment(it["appendAlignment"].toString()))
                    }
                }

                it.containsKey("appendHorizontalTabPosition") ->
                    builder.appendHorizontalTabPosition(it["appendHorizontalTabPosition"] as IntArray)

                it.containsKey("appendLogo") -> {
                    val size = if (it.containsKey("logoSize")) getLogoSize(it["logoSize"] as String) else getLogoSize("Normal")
                    builder.appendLogo(size, it["appendLogo"] as Int)
                }

                it.containsKey("appendBarcode") -> {
                    val sym   = if (it.containsKey("BarcodeSymbology")) getBarcodeSymbology(it["BarcodeSymbology"].toString()) else getBarcodeSymbology("Code128")
                    val width = if (it.containsKey("BarcodeWidth"))     getBarcodeWidth(it["BarcodeWidth"].toString()) else getBarcodeWidth("Mode2")
                    val height= if (it.containsKey("height"))           it["height"].toString().toInt() else 40
                    val hri   = if (it.containsKey("hri"))              it["hri"].toString().toBoolean() else true

                    when {
                        it.containsKey("absolutePosition") ->
                            builder.appendBarcodeWithAbsolutePosition(it["appendBarcode"].toString().toByteArray(encoding), sym, width, height, hri, it["absolutePosition"] as Int)
                        it.containsKey("alignment") ->
                            builder.appendBarcodeWithAlignment(it["appendBarcode"].toString().toByteArray(encoding), sym, width, height, hri, getAlignment(it["alignment"].toString()))
                        else ->
                            builder.appendBarcode(it["appendBarcode"].toString().toByteArray(encoding), sym, width, height, hri)
                    }
                }

                it.containsKey("appendBitmap") -> {
                    val diffusion = if (it.containsKey("diffusion")) it["diffusion"].toString().toBoolean() else true
                    val width     = if (it.containsKey("width"))     it["width"].toString().toInt() else 576
                    val bothScale = if (it.containsKey("bothScale")) it["bothScale"].toString().toBoolean() else true
                    val rotation  = if (it.containsKey("rotation"))  getConverterRotation(it["rotation"].toString()) else getConverterRotation("Normal")
                    try {
                        val path = it["appendBitmap"].toString()
                        val bitmap: Bitmap? = if (URLUtil.isValidUrl(path)) {
                            val imageUri = Uri.parse(path)
                            MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
                        } else {
                            BitmapFactory.decodeFile(path)
                        }
                        if (bitmap != null) {
                            when {
                                it.containsKey("absolutePosition") ->
                                    builder.appendBitmapWithAbsolutePosition(bitmap, diffusion, width, bothScale, rotation, it["absolutePosition"].toString().toInt())
                                it.containsKey("alignment") ->
                                    builder.appendBitmapWithAlignment(bitmap, diffusion, width, bothScale, rotation, getAlignment(it["alignment"].toString()))
                                else ->
                                    builder.appendBitmap(bitmap, diffusion, width, bothScale, rotation)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("FlutterStarPrnt", "appendBitmap failed", e)
                    }
                }

                it.containsKey("appendBitmapText") -> {
                    val fontSize  = if (it.containsKey("fontSize")) it["fontSize"].toString().toFloat() else 25f
                    val diffusion = if (it.containsKey("diffusion")) it["diffusion"].toString().toBoolean() else true
                    val width     = if (it.containsKey("width"))     it["width"].toString().toInt() else 576
                    val bothScale = if (it.containsKey("bothScale")) it["bothScale"].toString().toBoolean() else true
                    val text      = it["appendBitmapText"].toString()
                    val typeface  = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                    val bitmap    = createBitmapFromText(text, fontSize, width, typeface)
                    val rotation  = if (it.containsKey("rotation")) getConverterRotation(it["rotation"].toString()) else getConverterRotation("Normal")

                    when {
                        it.containsKey("absolutePosition") ->
                            builder.appendBitmapWithAbsolutePosition(bitmap, diffusion, width, bothScale, rotation, it["absolutePosition"] as Int)
                        it.containsKey("alignment") ->
                            builder.appendBitmapWithAlignment(bitmap, diffusion, width, bothScale, rotation, getAlignment(it["alignment"].toString()))
                        else ->
                            builder.appendBitmap(bitmap, diffusion, width, bothScale, rotation)
                    }
                }

                it.containsKey("appendBitmapByteArray") -> {
                    val diffusion = if (it.containsKey("diffusion")) it["diffusion"].toString().toBoolean() else true
                    val width     = if (it.containsKey("width"))     it["width"].toString().toInt() else 576
                    val bothScale = if (it.containsKey("bothScale")) it["bothScale"].toString().toBoolean() else true
                    val rotation  = if (it.containsKey("rotation"))  getConverterRotation(it["rotation"].toString()) else getConverterRotation("Normal")
                    try {
                        val byteArray = it["appendBitmapByteArray"] as ByteArray
                        val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                        if (bitmap != null) {
                            when {
                                it.containsKey("absolutePosition") ->
                                    builder.appendBitmapWithAbsolutePosition(bitmap, diffusion, width, bothScale, rotation, it["absolutePosition"].toString().toInt())
                                it.containsKey("alignment") ->
                                    builder.appendBitmapWithAlignment(bitmap, diffusion, width, bothScale, rotation, getAlignment(it["alignment"].toString()))
                                else ->
                                    builder.appendBitmap(bitmap, diffusion, width, bothScale, rotation)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("FlutterStarPrnt", "appendBitmapByteArray failed", e)
                    }
                }
            }
        }
    }

    private fun getEncoding(encoding: String): Charset = when (encoding) {
        "US-ASCII"       -> Charset.forName("US-ASCII")
        "Windows-1252"   -> try { Charset.forName("Windows-1252") } catch (_: UnsupportedCharsetException) { Charset.forName("UTF-8") }
        "Shift-JIS"      -> try { Charset.forName("Shift-JIS") }  catch (_: UnsupportedCharsetException) { Charset.forName("UTF-8") }
        "Windows-1251"   -> try { Charset.forName("Windows-1251") }catch (_: UnsupportedCharsetException) { Charset.forName("UTF-8") }
        "GB2312"         -> try { Charset.forName("GB2312") }     catch (_: UnsupportedCharsetException) { Charset.forName("UTF-8") }
        "Big5"           -> try { Charset.forName("Big5") }       catch (_: UnsupportedCharsetException) { Charset.forName("UTF-8") }
        "UTF-8"          -> Charset.forName("UTF-8")
        else             -> Charset.forName("US-ASCII")
    }

    private fun getCodePageType(codePageType: String): ICommandBuilder.CodePageType = when (codePageType) {
        "CP437"->CodePageType.CP437; "CP737"->CodePageType.CP737; "CP772"->CodePageType.CP772; "CP774"->CodePageType.CP774
        ; "CP851"->CodePageType.CP851; "CP852"->CodePageType.CP852; "CP855"->CodePageType.CP855; "CP857"->CodePageType.CP857
        ; "CP858"->CodePageType.CP858; "CP860"->CodePageType.CP860; "CP861"->CodePageType.CP861; "CP862"->CodePageType.CP862
        ; "CP863"->CodePageType.CP863; "CP864"->CodePageType.CP864; "CP865"->CodePageType.CP866; "CP869"->CodePageType.CP869
        ; "CP874"->CodePageType.CP874; "CP928"->CodePageType.CP928; "CP932"->CodePageType.CP932; "CP999"->CodePageType.CP999
        ; "CP1001"->CodePageType.CP1001; "CP1250"->CodePageType.CP1250; "CP1251"->CodePageType.CP1251; "CP1252"->CodePageType.CP1252
        ; "CP2001"->CodePageType.CP2001; "CP3001"->CodePageType.CP3001; "CP3002"->CodePageType.CP3002; "CP3011"->CodePageType.CP3011
        ; "CP3012"->CodePageType.CP3012; "CP3021"->CodePageType.CP3021; "CP3041"->CodePageType.CP3041; "CP3840"->CodePageType.CP3840
        ; "CP3841"->CodePageType.CP3841; "CP3843"->CodePageType.CP3843; "CP3845"->CodePageType.CP3845; "CP3846"->CodePageType.CP3846
        ; "CP3847"->CodePageType.CP3847; "CP3848"->CodePageType.CP3848; "UTF8"->CodePageType.UTF8; "Blank"->CodePageType.Blank
        else -> CodePageType.CP998
    }

    private fun getInternational(international: String): ICommandBuilder.InternationalType = when (international) {
        "UK"->ICommandBuilder.InternationalType.UK; "USA"->ICommandBuilder.InternationalType.USA
        ; "France"->ICommandBuilder.InternationalType.France; "Germany"->ICommandBuilder.InternationalType.Germany
        ; "Denmark"->ICommandBuilder.InternationalType.Denmark; "Sweden"->ICommandBuilder.InternationalType.Sweden
        ; "Italy"->ICommandBuilder.InternationalType.Italy; "Spain"->ICommandBuilder.InternationalType.Spain
        ; "Japan"->ICommandBuilder.InternationalType.Japan; "Norway"->ICommandBuilder.InternationalType.Norway
        ; "Denmark2"->ICommandBuilder.InternationalType.Denmark2; "Spain2"->ICommandBuilder.InternationalType.Spain2
        ; "LatinAmerica"->ICommandBuilder.InternationalType.LatinAmerica; "Korea"->ICommandBuilder.InternationalType.Korea
        ; "Ireland"->ICommandBuilder.InternationalType.Ireland; "Legal"->ICommandBuilder.InternationalType.Legal
        else -> ICommandBuilder.InternationalType.USA
    }

    private fun getFontStyle(fontStyle: String): ICommandBuilder.FontStyleType =
        if (fontStyle == "B") ICommandBuilder.FontStyleType.B else ICommandBuilder.FontStyleType.A

    private fun getCutPaperAction(cutPaperAction: String): ICommandBuilder.CutPaperAction = when (cutPaperAction) {
        "FullCut" -> CutPaperAction.FullCut
        "FullCutWithFeed" -> CutPaperAction.FullCutWithFeed
        "PartialCut" -> CutPaperAction.PartialCut
        "PartialCutWithFeed" -> CutPaperAction.PartialCutWithFeed
        else -> CutPaperAction.PartialCutWithFeed
    }

    private fun getPeripheralChannel(peripheralChannel: Int): ICommandBuilder.PeripheralChannel =
        if (peripheralChannel == 2) ICommandBuilder.PeripheralChannel.No2 else ICommandBuilder.PeripheralChannel.No1

    private fun getBlackMarkType(blackMarkType: String): ICommandBuilder.BlackMarkType = when (blackMarkType) {
        "Valid" -> ICommandBuilder.BlackMarkType.Valid
        "Invalid" -> ICommandBuilder.BlackMarkType.Invalid
        "ValidWithDetection" -> ICommandBuilder.BlackMarkType.ValidWithDetection
        else -> ICommandBuilder.BlackMarkType.Valid
    }

    private fun getAlignment(alignment: String): ICommandBuilder.AlignmentPosition = when (alignment) {
        "Center" -> ICommandBuilder.AlignmentPosition.Center
        "Right"  -> ICommandBuilder.AlignmentPosition.Right
        else     -> ICommandBuilder.AlignmentPosition.Left
    }

    private fun getLogoSize(logoSize: String): ICommandBuilder.LogoSize = when (logoSize) {
        "DoubleWidth"             -> ICommandBuilder.LogoSize.DoubleWidth
        "DoubleHeight"            -> ICommandBuilder.LogoSize.DoubleHeight
        "DoubleWidthDoubleHeight" -> ICommandBuilder.LogoSize.DoubleWidthDoubleHeight
        else -> ICommandBuilder.LogoSize.Normal
    }

    private fun getBarcodeSymbology(barcodeSymbology: String): ICommandBuilder.BarcodeSymbology = when (barcodeSymbology) {
        "Code128" -> ICommandBuilder.BarcodeSymbology.Code128
        "Code39"  -> ICommandBuilder.BarcodeSymbology.Code39
        "Code93"  -> ICommandBuilder.BarcodeSymbology.Code93
        "ITF"     -> ICommandBuilder.BarcodeSymbology.ITF
        "JAN8"    -> ICommandBuilder.BarcodeSymbology.JAN8
        "JAN13"   -> ICommandBuilder.BarcodeSymbology.JAN13
        "NW7"     -> ICommandBuilder.BarcodeSymbology.NW7
        "UPCA"    -> ICommandBuilder.BarcodeSymbology.UPCA
        "UPCE"    -> ICommandBuilder.BarcodeSymbology.UPCE
        else      -> ICommandBuilder.BarcodeSymbology.Code128
    }

    private fun getBarcodeWidth(barcodeWidth: String): ICommandBuilder.BarcodeWidth = when (barcodeWidth) {
        "Mode1"->ICommandBuilder.BarcodeWidth.Mode1; "Mode2"->ICommandBuilder.BarcodeWidth.Mode2
        ; "Mode3"->ICommandBuilder.BarcodeWidth.Mode3; "Mode4"->ICommandBuilder.BarcodeWidth.Mode4
        ; "Mode5"->ICommandBuilder.BarcodeWidth.Mode5; "Mode6"->ICommandBuilder.BarcodeWidth.Mode6
        ; "Mode7"->ICommandBuilder.BarcodeWidth.Mode7; "Mode8"->ICommandBuilder.BarcodeWidth.Mode8
        ; "Mode9"->ICommandBuilder.BarcodeWidth.Mode9
        else -> ICommandBuilder.BarcodeWidth.Mode2
    }

    private fun getConverterRotation(converterRotation: String): ICommandBuilder.BitmapConverterRotation = when (converterRotation) {
        "Left90"    -> ICommandBuilder.BitmapConverterRotation.Left90
        "Right90"   -> ICommandBuilder.BitmapConverterRotation.Right90
        "Rotate180" -> ICommandBuilder.BitmapConverterRotation.Rotate180
        else        -> ICommandBuilder.BitmapConverterRotation.Normal
    }

    private fun createBitmapFromText(printText: String, textSize: Float, printWidth: Int, typeface: Typeface): Bitmap {
        val paint = Paint().apply {
            this.textSize = textSize
            this.typeface = typeface
        }
        val textPaint = TextPaint(paint)
        val staticLayout = StaticLayout(
            printText,
            textPaint,
            printWidth,
            Layout.Alignment.ALIGN_NORMAL,
            1f,
            0f,
            false
        )
        val bitmap = Bitmap.createBitmap(staticLayout.width, staticLayout.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.translate(0f, 0f)
        staticLayout.draw(canvas)
        return bitmap
    }

    private fun sendCommand(
        portName: String,
        portSettings: String,
        commands: ByteArray,
        context: Context,
        @NonNull result: Result
    ) {
        var port: StarIOPort? = null
        var errorPosSting = ""
        try {
            port = StarIOPort.getPort(portName, portSettings, 10000, applicationContext)
            errorPosSting += "Port Opened,"
            try { Thread.sleep(100) } catch (_: InterruptedException) {}
            var status: StarPrinterStatus = port.beginCheckedBlock()
            val json: MutableMap<String, Any?> = mutableMapOf()
            errorPosSting += "got status for begin Check,"

            fun fillStatusFields(s: StarPrinterStatus) {
                json["offline"] = s.offline
                json["coverOpen"] = s.coverOpen
                json["cutterError"] = s.cutterError
                json["receiptPaperEmpty"] = s.receiptPaperEmpty
            }

            fillStatusFields(status)
            var isSuccess = true
            when {
                status.offline              -> { json["error_message"] = "A printer is offline"; isSuccess = false }
                status.coverOpen            -> { json["error_message"] = "Printer cover is open"; isSuccess = false }
                status.receiptPaperEmpty    -> { json["error_message"] = "Paper empty"; isSuccess = false }
                status.presenterPaperJamError -> { json["error_message"] = "Paper Jam"; isSuccess = false }
            }
            if (status.receiptPaperNearEmptyInner || status.receiptPaperNearEmptyOuter) {
                json["error_message"] = "Paper near empty"
            }

            if (isSuccess) {
                errorPosSting += "Writing to port,"
                port.writePort(commands, 0, commands.size)
                errorPosSting += "setting delay End check block,"
                port.setEndCheckedBlockTimeoutMillis(30000)
                errorPosSting += "doing End check block,"
                try { status = port.endCheckedBlock() } catch (e: Exception) {
                    errorPosSting += "End check block exception ${e}"
                }
                fillStatusFields(status)
                when {
                    status.offline              -> { json["error_message"] = "A printer is offline"; isSuccess = false }
                    status.coverOpen            -> { json["error_message"] = "Printer cover is open"; isSuccess = false }
                    status.receiptPaperEmpty    -> { json["error_message"] = "Paper empty"; isSuccess = false }
                    status.presenterPaperJamError -> { json["error_message"] = "Paper Jam"; isSuccess = false }
                }
            }

            json["is_success"] = isSuccess
            result.success(json)
        } catch (e: Exception) {
            result.error("STARIO_PORT_EXCEPTION", "${e.message} Failed After $errorPosSting", null)
        } finally {
            port?.let { try { StarIOPort.releasePort(it) } catch (_: Exception) {} }
        }
    }
}
