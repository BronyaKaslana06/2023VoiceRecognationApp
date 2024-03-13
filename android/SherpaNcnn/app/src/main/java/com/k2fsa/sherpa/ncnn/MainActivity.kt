package com.k2fsa.sherpa.ncnn

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.text.Layout
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.concurrent.thread

private const val TAG = "sherpa-ncnn"
private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

data class Message(val text: SpannableString, val type: MessageType)

enum class MessageType {
    USER, RESPONSE
}


class MainActivity : AppCompatActivity() {
    private val permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    // If there is a GPU and useGPU is true, we will use GPU
    // If there is no GPU and useGPU is true, we won't use GPU
    private val useGPU: Boolean = false

    private lateinit var model: SherpaNcnn
    private var audioRecord: AudioRecord? = null
    private lateinit var recordButton: Button
    private lateinit var textView: TextView
    private var recordingThread: Thread? = null

    private val audioSource = MediaRecorder.AudioSource.MIC
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO

    // Note: We don't use AudioFormat.ENCODING_PCM_FLOAT
    // since the AudioRecord.read(float[]) needs API level >= 23
    // but we are targeting API level >= 21
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var idx: Int = 0
    private var lastText: String = ""
//    private var endOrNot: Boolean = false
    private var num: Int = 0
    private var numString: String = ""
    private val messages = mutableListOf<Message>() //一个数组，用于存储每一条对话

    @Volatile
    private var isRecording: Boolean = false

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permissionToRecordAccepted = if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

        if (!permissionToRecordAccepted) {
            Log.e(TAG, "Audio record is disallowed")
            finish()
        }

        Log.i(TAG, "Audio record is permitted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)

        Log.i(TAG, "Start to initialize model")
        initModel()
        Log.i(TAG, "Finished initializing model")

        recordButton = findViewById(R.id.record_button)
        recordButton.setOnClickListener { onclick() }

        textView = findViewById(R.id.my_text)
        textView.movementMethod = ScrollingMovementMethod()
    }
    private fun displayMessages() {
        val spannableBuilder = SpannableStringBuilder()
        messages.forEach { message ->
            spannableBuilder.append(message.text)
            spannableBuilder.append("\n\n")
        }
        textView.text = spannableBuilder
    }

    private fun onclick() {
        if (!isRecording) {
            val ret = initMicrophone()
            if (!ret) {
                Log.e(TAG, "Failed to initialize microphone")
                return
            }
            Log.i(TAG, "state: ${audioRecord?.state}")
            audioRecord!!.startRecording()
            recordButton.setText(R.string.stop)
            isRecording = true
            model.reset()
            textView.text = ""
            lastText = ""
            idx = 0
            numString = ""
            messages.clear()

            recordingThread = thread(true) {
                processSamples()
            }
            Log.i(TAG, "Started recording")
        } else {
            isRecording = false
            audioRecord!!.stop()
            audioRecord!!.release()
            audioRecord = null
            recordButton.setText(R.string.start)
            Log.i(TAG, "Stopped recording")
            numString = ""
            textView.text = ""
            lastText = ""
            idx = 0
            numString = ""
            messages.clear()
        }
    }
    private fun outputNumber(text: String): Int?{
        val chineseToArabicMapping = mapOf(
            "负一" to -1, "负二" to -2, "负三" to -3, "负四" to -4, "负五" to -5,
            "地下一" to -1, "地下二" to -2, "地下三" to -3, "地下四" to -4, "地下五" to -5,
            "九十九楼" to 99, "九十八楼" to 98, "九十七楼" to 97, "九十六楼" to 96, "九十五楼" to 95,
            "九十四楼" to 94, "九十三楼" to 93, "九十二楼" to 92, "九十一楼" to 91, "九十楼" to 90,
            "八十九楼" to 89, "八十八楼" to 88, "八十七楼" to 87, "八十六楼" to 86, "八十五楼" to 85,
            "八十四楼" to 84, "八十三楼" to 83, "八十二楼" to 82, "八十一楼" to 81, "八十楼" to 80,
            "七十九楼" to 79, "七十八楼" to 78, "七十七楼" to 77, "七十六楼" to 76, "七十五楼" to 75,
            "七十四楼" to 74, "七十三楼" to 73, "七十二楼" to 72, "七十一楼" to 71, "七十楼" to 70,
            "六十九楼" to 69, "六十八楼" to 68, "六十七楼" to 67, "六十六楼" to 66, "六十五楼" to 65,
            "六十四楼" to 64, "六十三楼" to 63, "六十二楼" to 62, "六十一楼" to 61, "六十楼" to 60,
            "五十九楼" to 59, "五十八楼" to 58, "五十七楼" to 57, "五十六楼" to 56, "五十五楼" to 55,
            "五十四楼" to 54, "五十三楼" to 53, "五十二楼" to 52, "五十一楼" to 51, "五十楼" to 50,
            "四十九楼" to 49, "四十八楼" to 48, "四十七楼" to 47, "四十六楼" to 46, "四十五楼" to 45,
            "四十四楼" to 44, "四十三楼" to 43, "四十二楼" to 42, "四十一楼" to 41, "四十楼" to 40,
            "三十九楼" to 39, "三十八楼" to 38, "三十七楼" to 37, "三十六楼" to 36, "三十五楼" to 35,
            "三十四楼" to 34, "三十三楼" to 33, "三十二楼" to 32, "三十一楼" to 31, "三十楼" to 30,
            "二十九楼" to 29, "二十八楼" to 28, "二十七楼" to 27, "二十六楼" to 26, "二十五楼" to 25,
            "二十四楼" to 24, "二十三楼" to 23, "二十二楼" to 22, "二十一楼" to 21, "二十楼" to 20,
            "十九楼" to 19, "十八楼" to 18, "十七楼" to 17, "十六楼" to 16, "十五楼" to 15,
            "十四楼" to 14, "十三楼" to 13, "十二楼" to 12, "十一楼" to 11, "十楼" to 10,
            "九楼" to 9, "八楼" to 8, "七楼" to 7, "六楼" to 6, "五楼" to 5,
            "四楼" to 4, "三楼" to 3, "二楼" to 2, "两楼" to 2, "一楼" to 1,
            "九十九层" to 99, "九十八层" to 98, "九十七层" to 97, "九十六层" to 96, "九十五层" to 95,
            "九十四层" to 94, "九十三层" to 93, "九十二层" to 92, "九十一层" to 91, "九十层" to 90,
            "八十九层" to 89, "八十八层" to 88, "八十七层" to 87, "八十六层" to 86, "八十五层" to 85,
            "八十四层" to 84, "八十三层" to 83, "八十二层" to 82, "八十一层" to 81, "八十层" to 80,
            "七十九层" to 79, "七十八层" to 78, "七十七层" to 77, "七十六层" to 76, "七十五层" to 75,
            "七十四层" to 74, "七十三层" to 73, "七十二层" to 72, "七十一层" to 71, "七十层" to 70,
            "六十九层" to 69, "六十八层" to 68, "六十七层" to 67, "六十六层" to 66, "六十五层" to 65,
            "六十四层" to 64, "六十三层" to 63, "六十二层" to 62, "六十一层" to 61, "六十层" to 60,
            "五十九层" to 59, "五十八层" to 58, "五十七层" to 57, "五十六层" to 56, "五十五层" to 55,
            "五十四层" to 54, "五十三层" to 53, "五十二层" to 52, "五十一层" to 51, "五十层" to 50,
            "四十九层" to 49, "四十八层" to 48, "四十七层" to 47, "四十六层" to 46, "四十五层" to 45,
            "四十四层" to 44, "四十三层" to 43, "四十二层" to 42, "四十一层" to 41, "四十层" to 40,
            "三十九层" to 39, "三十八层" to 38, "三十七层" to 37, "三十六层" to 36, "三十五层" to 35,
            "三十四层" to 34, "三十三层" to 33, "三十二层" to 32, "三十一层" to 31, "三十层" to 30,
            "二十九层" to 29, "二十八层" to 28, "二十七层" to 27, "二十六层" to 26, "二十五层" to 25,
            "二十四层" to 24, "二十三层" to 23, "二十二层" to 22, "二十一层" to 21, "二十层" to 20,
            "十九层" to 19, "十八层" to 18, "十七层" to 17, "十六层" to 16, "十五层" to 15,
            "十四层" to 14, "十三层" to 13, "十二层" to 12, "十一层" to 11, "十层" to 10,
            "九层" to 9, "八层" to 8, "七层" to 7, "六层" to 6, "五层" to 5,
            "四层" to 4, "三层" to 3, "二层" to 2, "两层" to 2, "一层" to 1,
            "FIFTH FLOOR UNDERGROUND" to -5, "FOURTH FLOOR UNDERGROUND" to -4, "THIRD FLOOR UNDERGROUND" to -3, "SECOND FLOOR UNDERGROUND" to -2,
            "FIRST FLOOR UNDERGROUND" to -1,
            "NINETY NINTH" to 99, "NINETY EIGHTH" to 98, "NINETY SEVENTH" to 97, "NINETY SIXTH" to 96, "NINETY FIFTH" to 95,
            "NINETY FOURTH" to 94, "NINETY THIRD" to 93, "NINETY SECOND" to 92, "NINETY FIRST" to 91, "NINETIETH" to 90,
            "EIGHTY NINTH" to 89, "EIGHTY EIGHTH" to 88, "EIGHTY SEVENTH" to 87, "EIGHTY SIXTH" to 86, "EIGHTY FIFTH" to 85,
            "EIGHTY FOURTH" to 84, "EIGHTY THIRD" to 83, "EIGHTY SECOND" to 82, "EIGHTY FIRST" to 81, "EIGHTIETH" to 80,
            "SEVENTY NINTH" to 79, "SEVENTY EIGHTH" to 78, "SEVENTY SEVENTH" to 77, "SEVENTY SIXTH" to 76, "SEVENTY FIFTH" to 75,
            "SEVENTY FOURTH" to 74, "SEVENTY THIRD" to 73, "SEVENTY SECOND" to 72, "SEVENTY FIRST" to 71, "SEVENTIETH" to 70,
            "SIXTY NINTH" to 69, "SIXTY EIGHTH" to 68, "SIXTY SEVENTH" to 67, "SIXTY SIXTH" to 66, "SIXTY FIFTH" to 65,
            "SEVENTY FOURTH" to 64, "SEVENTY THIRD" to 63, "SEVENTY SECOND" to 62, "SEVENTY FIRST" to 61, "SEVENTIETH" to 60,
            "FIFTY NINTH" to 59, "FIFTY EIGHTH" to 58, "FIFTY SEVENTH" to 57, "FIFTY SIXTH" to 56, "FIFTY FIFTH" to 55,
            "FIFTY FOURTH" to 54, "FIFTY THIRD" to 53, "FIFTY SECOND" to 52, "FIFTY FIRST" to 51, "FIFTIETH" to 50,
            "FORTY NINTH" to 49, "FORTY EIGHTH" to 48, "FORTY SEVENTH" to 47, "FORTY SIXTH" to 46, "FORTY FIFTH" to 45,
            "FORTY FOURTH" to 44, "FORTY THIRD" to 43, "FORTY SECOND" to 42, "FORTY FIRST" to 41, "FORTIETH" to 40,
            "THIRTY NINTH" to 39, "THIRTY EIGHTH" to 38, "THIRTY SEVENTH" to 37, "THIRTY SIXTH" to 36, "THIRTY FIFTH" to 35,
            "THIRTY FOURTH" to 34, "THIRTY THIRD" to 33, "THIRTY SECOND" to 32, "THIRTY FIRST" to 31, "THIRTIETH" to 30,
            "TWENTY NINTH" to 29, "TWENTY EIGHTH" to 28, "TWENTY SEVENTH" to 27, "TWENTY SIXTH" to 26, "TWENTY FIFTH" to 25,
            "TWENTY FOURTH" to 24, "TWENTY THIRD" to 23, "TWENTY SECOND" to 22, "TWENTY FIRST" to 21, "TWENTIETH" to 20,
            "NINETEENTH" to 19, "EIGHTEENTH" to 18, "SEVENTEENTH" to 17, "SIXTEENTH" to 16, "FIFTEENTH" to 15,
            "FOURTEENTH" to 14, "THIRTEENTH" to 13, "TWELFTH" to 12, "ELEVENTH" to 11, "TENTH" to 10,
            "NINTH" to 9, "EIGHTH" to 8, "SEVENTH" to 7, "SIXTH" to 6, "FIFTH" to 5,
            "FOURTH" to 4, "THIRD" to 3, "SECOND" to 2, "FIRST" to 1
        )

        for ((chinese, arabic) in chineseToArabicMapping) {
            val startIndex = text.indexOf(chinese)
            if (startIndex != -1) {
                return arabic
            }
        }
        return null
    }

    private fun addMessage(text: String, type: MessageType) {
        val prefix: String
        val spannableString: SpannableString
        val colorSpan: ForegroundColorSpan

        when (type) {
            MessageType.USER -> {
                prefix = "用户：$text"
                colorSpan = ForegroundColorSpan(Color.BLUE)
            }
            MessageType.RESPONSE -> {
                prefix = text
                colorSpan = ForegroundColorSpan(Color.RED)
            }
        }

        spannableString = SpannableString(prefix)
        spannableString.setSpan(colorSpan, 0, prefix.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

        val alignmentSpan = AlignmentSpan.Standard(
            if (type == MessageType.USER)
                Layout.Alignment.ALIGN_NORMAL
            else
                Layout.Alignment.ALIGN_OPPOSITE
        )
        spannableString.setSpan(alignmentSpan, 0, prefix.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

        messages.add(Message(spannableString, type))
        displayMessages()
    }

    private fun processSamples() {
        Log.i(TAG, "processing samples")

        val interval = 0.1
        val bufferSize = (interval * sampleRateInHz).toInt() // in samples
        val buffer = ShortArray(bufferSize)

        var userStart = false
        var emptyOp = true //防止不重复
        var awaitingCoffeeType = false  //咖啡的逻辑
        var awaitingCoffeeTemperature = false   //咖啡的逻辑
        var coffeeProcessOver = true
        var coffeeType = "" //咖啡类型
        var coffeeTemp = "" //咖啡温度

        while (isRecording) {
            val ret = audioRecord?.read(buffer, 0, buffer.size)
            if (ret != null && ret > 0) {
                val samples = FloatArray(ret) { buffer[it] / 32768.0f }
                model.acceptSamples(samples)
                while (model.isReady()) {
                    model.decode()
                }

                runOnUiThread {
                    val isEndpoint = model.isEndpoint()
                    var now_num: Int? = null

                    val text = model.text
                    // 判断用户是否开始说话
                    if(!userStart) {
                        if (text.isNotBlank()) {
                            userStart = true
                        }
                    }
                    //若用户一句话已说完
                    else if(isEndpoint) {
                        //检查语音识别内容是否有变化
                        if (text.isNotBlank() && text != lastText) {
                            lastText = text
                            emptyOp = true
                            //检查是否含有电梯楼层
                            now_num = outputNumber(text)    // now_num
                            if (now_num != null) {
                                if (num == 0) {
                                    num = now_num // 如果num为0，将当前结果添加到numString
                                }
                                else if(num != now_num) {
                                    num = now_num
                                }
                            }
                            // 含有电梯楼层，回复
                            if (num!=0 && emptyOp) {
                                addMessage(lastText, MessageType.USER)
                                addMessage("您要去第 $num 楼", MessageType.RESPONSE)
                                model.reset()
                                emptyOp = false
                                num = 0
                            }
                            // 不含电梯楼层
                            else if(num==0 && emptyOp) {
                                Log.d(TAG, "Processing user input: $lastText")
                                // 点咖啡的逻辑
                                if (lastText.contains("咖啡") && coffeeProcessOver) {
                                    addMessage(lastText, MessageType.USER)
                                    addMessage("好的，您是需要拿铁还是美式？", MessageType.RESPONSE)
                                    awaitingCoffeeType = true
                                    coffeeProcessOver = false
                                    lastText = ""
                                    model.reset()
                                }
                                //等待顾客回复咖啡类型
                                else if (awaitingCoffeeType) {
                                    coffeeType = when {
                                        "拿铁" in lastText -> "拿铁"
                                        "美式" in lastText -> "美式"
                                        else -> ""
                                    }
                                    if (coffeeType.isNotEmpty()) {
                                        addMessage(lastText, MessageType.USER)
                                        addMessage("您需要冰咖啡还是热咖啡？", MessageType.RESPONSE)
                                        awaitingCoffeeType = false
                                        awaitingCoffeeTemperature = true
                                        lastText = ""
                                        model.reset()
                                    }
                                }
                                //等待顾客回复咖啡温度
                                else if (awaitingCoffeeTemperature) {
                                    coffeeTemp = when {
                                        "冰" in lastText -> "冰"
                                        "热" in lastText -> "热"
                                        else -> ""
                                    }
                                    if (coffeeTemp.isNotEmpty() && !coffeeProcessOver) {
                                        addMessage(lastText, MessageType.USER)
                                        addMessage("您想要的是一份${coffeeTemp}${coffeeType}", MessageType.RESPONSE)
                                        awaitingCoffeeTemperature = false
                                        lastText = ""
                                        coffeeProcessOver = true
                                        model.reset()
                                    }
                                }
                                else {
                                    addMessage(lastText, MessageType.USER)
//                                    addMessage("没有听懂您说的什么，请再重复一遍", MessageType.RESPONSE)
//                                    model.reset()
                                    num = 0
                                    emptyOp = false
                                    model.reset()
                                }
                            }
                        }

                    }

                }
            }
        }
    }

    private fun initMicrophone(): Boolean {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
            return false
        }

        val numBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
        Log.i(
            TAG,
            "buffer size in milliseconds: ${numBytes * 1000.0f / sampleRateInHz}"
        )

        audioRecord = AudioRecord(
            audioSource,
            sampleRateInHz,
            channelConfig,
            audioFormat,
            numBytes * 2 // a sample has two bytes as we are using 16-bit PCM
        )
        return true
    }

    private fun initModel() {
        val featConfig = getFeatureExtractorConfig(
            sampleRate = 16000.0f,
            featureDim = 80
        )
        //Please change the argument "type" if you use a different model
        val modelConfig = getModelConfig(type = 2, useGPU = useGPU)!!
        val decoderConfig = getDecoderConfig(method = "greedy_search", numActivePaths = 4)

        val config = RecognizerConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
            decoderConfig = decoderConfig,
            enableEndpoint = true,
            rule1MinTrailingSilence = 2.0f,
            rule2MinTrailingSilence = 0.8f,
            rule3MinUtteranceLength = 20.0f,
        )

        model = SherpaNcnn(
            assetManager = application.assets,
            config = config,
        )
    }
}
