# StreetWise appliance hardware
The hardware for the StreetWise appliance is designed to be simple and robust, and easily modifiable in order to allow multiple refinments and differing versions to be deployed. The version described here is based on a [Raspberry Pi 3B+](https://www.raspberrypi.org/products/raspberry-pi-3-model-b-plus/), and uses components from an [AIY Voice Kit V1](https://aiyprojects.withgoogle.com/voice-v1/) for its microphone, speaker and button. Use of the Voice Kit is optional, but it does provide a straightforward solution for high-quality sound input and output on the Raspberry Pi.

Full kit list:
* [Raspberry Pi 3B+](https://thepihut.com/products/raspberry-pi-3-model-b-plus)
* [Power supply](https://thepihut.com/collections/raspberry-pi-power-supplies/products/official-raspberry-pi-universal-power-supply)
* [SD card](https://www.amazon.co.uk/dp/B06XFSZGCC/)
* [AIY Voice Kit V1](https://aiyprojects.withgoogle.com/voice-v1/)
* [Mini OLED display](https://thepihut.com/products/adafruit-monochrome-1-3-128x64-oled-graphic-display)
* [12-key keypad](https://uk.rs-online.com/web/p/keypads/0146014/)
* [4G dongle](https://www.ebay.com/itm/191123940329)

The hardware is encased in a [laser-cut box](box.svg) made of [3mm acrylic](https://uk.rs-online.com/web/p/solid-plastic-sheets/0824654/).  
The colours of the [laser-cut box template](box.svg) correspond to different modes and orders of cuts.

| Order | Colour | Mode    | Speed | Power |
|------:|:-------|:--------|:------|:------|
|    1. | Red    | Engrave | 200   | 100   |
|    2. | Blue   | Cut     | 14    | 80    |
|    3. | Black  | Cut     | 14    | 80    |


## Wiring

Follow [the instructions on AIY Voice Kit](https://aiyprojects.withgoogle.com/voice-v1/) on how to wire up the button (with integrated LED), microphone, and speaker.

### Keypad

Next connect the keypad.

| Keypad Pin    | VoiceHAT                 | Physical PIN                    |
| ------------: | :----------------------- | :------------------------------ |
| K             | Driver 1                 | 11                              |
| J             | Driver 2                 | 13                              |
| H             | Driver 3                 | 15                              |
| G             | Servo 0                  | 37                              |
| F             | Servo 2                  | 33                              |
| E             | Servo 4                  | 32                              |
| D             | Servo 5                  | 18                              |

Pins K-G are the row pins and F-D are the column pins, in case you use a different keypad.
 
### OLED Display

And finally wire up the OLED display

| Display Pin   | VoiceHAT                 | Physical PIN                    |
| ------------: | :----------------------- | :------------------------------ |
| Ground        | GND (SPI)                | 6                               |
| Voltage In    | 3.3V (SPI)               | 1                               |
| 3v3           | Nothing                  | Nothing                         |
| Chip Select   | CE0                      | 24                              |
| Reset         | SDA (I2C)                | 3                               |
| Data/Command  | SCL (I2C)                | 5                               |
| Clock         | CLK (SPI)                | 23                              |
| Data          | MOSI (SPI)               | 19                              |


## OS Setup

### Create The SD Card
1. Download & Flash [AIY Voicekit Image](https://github.com/google/aiyprojects-raspbian/releases) or [Raspbian Stretch](https://www.raspberrypi.org/downloads/raspbian/)
2. Enable ssh & wifi & keypad
  - Create an empty file, named `ssh`, in the boot partition of the microSD.
  - If you're connecting to the Pi using wifi, create another file name `wpa_supplicant.conf` in the boot partition:
    ```
    country=US
    ctrl_interface=DIR=/var/run/wpa_supplicant GROUP=netdev
    update_config=1
    
    network={
        ssid="your_real_wifi_ssid"
        scan_ssid=1
        psk="your_real_password"
        key_mgmt=WPA-PSK
    }
    ```

3. Configure keypad device tree overlay driver

Copy the [3x4matrix.dtbo](3x4matrix.dtbo) file to overlay directory of boot partition. 

4. Edit `config.txt` file in boot partition to include:
```
# Enable 3x4 matrix keypad
dtoverlay=3x4matrix

# Enable SPI for display
dtparam=spi=on
```

### Boot up and connect to the pi using ssh

Follow [the instructions on AIY Voice Kit](https://aiyprojects.withgoogle.com/voice-v1/#users-guide--ssh-to-your-kit) if you're unsure how to do this.

1. make sure you change the password `passwd`

2. Install all dependencies

sudo apt install oracle-java8-jdk
sudo apt install input-utils
sudo apt install git
sudo apt install wiringpi

3. Test keypad

lsinput
```
/dev/input/event0
   bustype : BUS_HOST
   vendor  : 0x0
   product : 0x0
   version : 0
   name    : "MATRIX3x4"
   bits ev : EV_SYN EV_KEY EV_MSC EV_REP
```

Next check if the button presses set off key pressed and released events.
input-events 0
```
/dev/input/event0
   bustype : BUS_HOST
   vendor  : 0x0
   product : 0x0
   version : 0
   name    : "MATRIX3x4"
   bits ev : EV_SYN EV_KEY EV_MSC EV_REP

waiting for events
14:21:51.857979: EV_MSC MSC_SCAN 0
14:21:51.857979: EV_KEY KEY_N (0x31) pressed
14:21:51.857979: EV_SYN code=0 value=0
14:21:51.947982: EV_MSC MSC_SCAN 0
14:21:51.947982: EV_KEY KEY_N (0x31) released
14:21:51.947982: EV_SYN code=0 value=0
```

That's it on the Hardware / OS side. Next step is to get the [appliance](../streetwise-appliance) running.

## License
Apache 2.0
