package ac.robinson.pss2

import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.Oscilloscope
import com.dgis.input.evdev.EventDevice
import com.dgis.input.evdev.InputEvent
import com.pi4j.io.gpio.GpioFactory
import com.pi4j.io.gpio.RaspiPin
import com.pi4j.io.spi.SpiChannel
import com.pi4j.io.spi.SpiFactory
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.subjects.PublishSubject
import ssd1306.Display
import java.awt.BasicStroke
import java.awt.Font
import java.awt.event.KeyEvent
import java.util.concurrent.TimeUnit


class PssIO : Oscilloscope.OscilloscopeEventHandler {
    override fun handleEvent(data: FloatArray?, event: AudioEvent?) {
        if (data == null) return
        audioEvents.onNext(data)
    }

    private var inputEnabled = true
    private val displayBuffer = Array(4) { '_' }
    private var currentPos = -1
    private var keypressPipeline: Observable<Char>? = null
    private var keypressDisposable: Disposable? = null
    private val fn = "/dev/input/event0"
    private val dev: EventDevice = EventDevice(fn)
    private val inputEvents: PublishSubject<InputEvent> = PublishSubject.create()
    private val audioEvents: PublishSubject<FloatArray> = PublishSubject.create()
    private var audioEventsDisposable: Disposable? = null
    private var fadingNumber = true

    private var oledDisplay: Display? = null

    //Final question numbers get published on this Subject
    //Subscribe to it to have them delivered in Pss2
    val answerQueryNumbers: PublishSubject<Int> = PublishSubject.create()


    //Call this method to initialize the keypad
    fun initialize() {
        oledDisplay = Display(128, 64, GpioFactory.getInstance(),
                SpiFactory.getInstance(SpiChannel.CS0, 8000000), RaspiPin.GPIO_08, RaspiPin.GPIO_09)
        oledDisplay?.begin()
        dev.addListener { e -> inputEvents.onNext(e) } //Publish raw input events


        keypressPipeline = inputEvents
                .filter { e -> e.type == InputEvent.EV_KEY } // Listen only for key presses
                .filter { e -> e.value == 0 } // Listen only for key releases
                .filter { e -> keyCodeIsNumber(e.code.toInt()) } //Listen only for number keypresses
                .takeWhile { inputEnabled } //Listen only when we're actually interested in the input
                .map { e -> getCharFromKeycode(e.code.toInt()) } //Convert raw keycode into Char
                .timeout(3, TimeUnit.SECONDS) //Input times out if no new numbers
                .doOnError { clearDisplay() }  //Clear display after timeout error
                .retry() //resubscribe after timeout occurs

    }

    fun destroy() {
        //Stop listening for keypresses
        dev.close()

        //Dispose of the reactive pipeline
        keypressDisposable?.dispose()

        //Clear the display & buffer
        clearDisplay()
    }

    private var fadeDisposable: Disposable? = null
    //Call this method whenever Pss2 is ready to receive user input
    //E.g. when Pss2 is idle
    fun enableUserInput(fade: Boolean) {

        //Clear the display & buffer
        if (fade) {
            fadingNumber = true
            resetBuffer()
            fadeDisposable = Observable.just(true).delay(10, TimeUnit.SECONDS).subscribe {
                System.out.println("Resetting fade")
                fadingNumber = false
                clearDisplay()
            }
        } else {
            clearDisplay()
        }

        inputEnabled = true
        if (keypressDisposable != null) {
            keypressDisposable!!.dispose()
        }
        keypressDisposable = keypressPipeline?.subscribeBy(
                onNext = {
                    addToDisplay(it) //Display character
                },
                onError = {
                    println(it) //Report but otherwise ignore timeout errors
                },
                onComplete = { println("Complete") }
        )
    }

    //Call this method to block User input
    //e.g. when displaying a question number, playing an answer, or recording a question
    fun blockUserInput() {
        fadingNumber = false
        fadeDisposable?.dispose()
        inputEnabled = false
        keypressDisposable?.dispose()
    }

    //Display a number
    //Make sure you call blockUserInput first
    fun displayNumber(number: Int) {
        println("Displaying: $number")
        clearDisplay()
        displayOledText(number.toString())
        return
    }

    private fun displayOledText(text: String) {
        if (oledDisplay != null) {
            oledDisplay?.clear()
            oledDisplay?.graphics?.font = Font(Font.MONOSPACED, Font.PLAIN, 55)
            oledDisplay?.graphics?.drawString(text, 0, 50)
            oledDisplay?.displayImage()
            oledDisplay?.display()
        }
    }

    private fun resetBuffer() {
        //Reset the buffer and position
        displayBuffer[0] = '_'
        displayBuffer[1] = '_'
        displayBuffer[2] = '_'
        displayBuffer[3] = '_'
        currentPos = -1
    }

    fun clearDisplay() {
        if (displayBuffer[0] != '_' || displayBuffer[1] != '_' ||
                displayBuffer[2] != '_' || displayBuffer[3] != '_')
            println("Clearing partial input")

        if (!fadingNumber) {
            oledDisplay?.clear()
            oledDisplay?.display()
        }

        resetBuffer()

    }


    private fun addToDisplay(character: Char) {
        if (fadingNumber) {
            fadingNumber = false
            fadeDisposable?.dispose()
            clearDisplay()
        }
        //Increment the position
        if (currentPos + 1 < displayBuffer.size) {
            currentPos++
        } else { //roll over if it's the fifth character
            clearDisplay()
            addToDisplay(character)
            return
        }
        displayBuffer[currentPos] = character
        val number = "${displayBuffer[0]}${displayBuffer[1]}${displayBuffer[2]}${displayBuffer[3]}"
        println("Displaying: $number")
        displayOledText(number)
        if (currentPos == displayBuffer.size - 1) {
            inputEnabled = false
            val n = number.toIntOrNull()
            if (n != null) {
                println(n)
                answerQueryNumbers.onNext(n)
            }
        }
    }

    fun displayIndeterminateProgress(start: Int, end: Int) {
        oledDisplay?.clear()
        oledDisplay?.graphics?.stroke = BasicStroke(3f)
        oledDisplay?.graphics?.drawArc(41, 9, 46, 46, start, end)
        oledDisplay?.displayImage()
    }

    private fun keyCodeIsNumber(code: Int): Boolean {
        return when (code) {
            KeyEvent.VK_0 -> true
            KeyEvent.VK_1 -> true
            KeyEvent.VK_2 -> true
            KeyEvent.VK_3 -> true
            KeyEvent.VK_4 -> true
            KeyEvent.VK_5 -> true
            KeyEvent.VK_6 -> true
            KeyEvent.VK_7 -> true
            KeyEvent.VK_8 -> true
            KeyEvent.VK_9 -> true
            else -> false
        }
    }

    private fun getCharFromKeycode(code: Int): Char {
        return when (code) {
            KeyEvent.VK_0 -> '0'
            KeyEvent.VK_1 -> '1'
            KeyEvent.VK_2 -> '2'
            KeyEvent.VK_3 -> '3'
            KeyEvent.VK_4 -> '4'
            KeyEvent.VK_5 -> '5'
            KeyEvent.VK_6 -> '6'
            KeyEvent.VK_7 -> '7'
            KeyEvent.VK_8 -> '8'
            KeyEvent.VK_9 -> '9'
            else -> '0'
        }
    }

    fun stopOscilloscope() {
        audioEventsDisposable?.dispose()
        clearDisplay()
    }

    fun startOscilloscope() {
        audioEventsDisposable = audioEvents
                .sample(30, TimeUnit.MILLISECONDS)
                .subscribe {
                    val data = it
                    oledDisplay?.clear()
                    var x = 0
                    for (i in 0 until data.size) {
                        if (i % 2 != 0 && (i - 1) % 8 == 0) {
                            var y = (data[i] * 100).toInt()
                            if (y < -31) y = -31
                            if (y > 31) y = 31
                            oledDisplay?.graphics?.drawLine(x, 32, x, 32 + y)
                            x++
                        }
                    }
                    oledDisplay?.displayImage()
                }
    }
}
